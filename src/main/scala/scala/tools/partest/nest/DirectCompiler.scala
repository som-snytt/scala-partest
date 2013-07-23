/* NEST (New Scala Test)
 * Copyright 2007-2013 LAMP/EPFL
 * @author Philipp Haller
 */

package scala.tools.partest
package nest

import scala.tools.nsc.{ Global, Settings, CompilerCommand, FatalError }
import scala.tools.nsc.reporters.{ Reporter, ConsoleReporter }
import scala.tools.nsc.util.{ FakePos, stackTraceString }
import scala.tools.nsc.Properties.{ setProp, propOrEmpty }
import scala.reflect.io.AbstractFile
import scala.reflect.internal.util.Position
import java.io.{ BufferedReader, PrintWriter, FileReader, Writer, FileWriter }

class ExtConsoleReporter(settings: Settings, val writer: PrintWriter) extends ConsoleReporter(settings, Console.in, writer) {
  shortname = true
  // override def error(pos: Position, msg: String): Unit
}

class TestSettings(cp: String, error: String => Unit) extends Settings(error) {
  def this(cp: String) = this(cp, _ => ())

  nowarnings.value  = false
  encoding.value    = "UTF-8"
  classpath.value   = cp
}

class PartestGlobal(settings: Settings, reporter: Reporter) extends Global(settings, reporter) {
  // override def abort(msg: String): Nothing
  // override def globalError(msg: String): Unit
  // override def supplementErrorMessage(msg: String): String
}
class DirectCompiler(val fileManager: FileManager) {
  def newGlobal(settings: Settings, reporter: Reporter): PartestGlobal =
    new PartestGlobal(settings, reporter)

  def newGlobal(settings: Settings, logWriter: FileWriter): Global =
    newGlobal(settings, new ExtConsoleReporter(settings, new PrintWriter(logWriter)))

  def compile(runner: Runner, opts0: List[String], sources: List[File]): TestState = {
    import runner.{ sources => _, _ }
    import ClassPath.{join, split}

    // adding codelib.jar to the classpath
    // codelib provides the possibility to override standard reify
    // this shields the massive amount of reification tests from changes in the API
    val codeLib = PathSettings.srcCodeLib.fold[List[Path]](x => Nil, lib => List[Path](lib))
    // add the instrumented library version to classpath -- must come first
    val specializedOverride: List[Path] = if (kind == "specialized") List(PathSettings.srcSpecLib.fold(sys.error, identity)) else Nil

    val classPath: List[Path] = specializedOverride ++ codeLib ++ fileManager.testClassPath ++ List[Path](outDir)

    val testSettings = new TestSettings(fileManager.joinPaths(classPath))
    val logWriter    = new FileWriter(logFile)
    val srcDir       = if (testFile.isDirectory) testFile else Path(testFile).parent.jfile
    val opts         = fileManager.updatePluginPath(opts0, AbstractFile getDirectory outDir, AbstractFile getDirectory srcDir)
    val command      = new CompilerCommand(opts, testSettings)
    val global       = newGlobal(testSettings, logWriter)
    val reporter     = global.reporter.asInstanceOf[ExtConsoleReporter]
    def errorCount   = reporter.ERROR.count

    testSettings.outputDirs setSingleOutput outDir.getPath

    // check that option processing succeeded
    if (opts0.nonEmpty && !command.ok)
      reporter.error(null, opts0.mkString("bad options: ", space, ""))

    def ids = sources.map(_.testIdent) mkString space
    vlog(s"% scalac $ids")

    def execCompile() =
      if (command.shouldStopWithInfo) {
        logWriter append (command getInfoMessage global)
        runner genFail "compilation stopped with info"
      } else {
        new global.Run compile sources.map(_.getPath)
        if (!reporter.hasErrors) runner.genPass()
        else {
          reporter.printSummary()
          reporter.writer.close()
          runner.genFail(s"compilation failed with $errorCount errors")
        }
      }

    try     { execCompile() }
    catch   { case t: Throwable => reporter.error(null, t.getMessage) ; runner.genCrash(t) }
    finally { logWriter.close() }
  }
}
