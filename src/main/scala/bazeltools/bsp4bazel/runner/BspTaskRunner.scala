package bazeltools.bsp4bazel.runner

import bazeltools.bsp4bazel.FilesIO
import bazeltools.bsp4bazel.Logger
import bazeltools.bsp4bazel.protocol.BspServer
import bazeltools.bsp4bazel.protocol.BuildTargetIdentifier
import bazeltools.bsp4bazel.protocol.TextDocumentIdentifier
import bazeltools.bsp4bazel.protocol.UriFactory
import bazeltools.bsp4bazel.protocol.BuildTarget
import bazeltools.bsp4bazel.protocol.BuildTargetCapabilities
import bazeltools.bsp4bazel.protocol.ScalaBuildTarget
import bazeltools.bsp4bazel.protocol.ScalaPlatform
import bazeltools.bsp4bazel.protocol.ScalacOptionsItem
import cats.effect.IO
import cats.effect.kernel.Resource
import cats.syntax.all._
import fs2.Stream
import io.bazel.rules_scala.diagnostics.diagnostics.Diagnostic
import io.bazel.rules_scala.diagnostics.diagnostics.FileDiagnostics
import io.bazel.rules_scala.diagnostics.diagnostics.TargetDiagnostics
import io.circe.Decoder
import io.circe.generic.semiauto.*
import io.circe.syntax._
import cats.data.NonEmptyList

import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.FileVisitOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._
import scala.util.Failure
import scala.util.Success
import scala.util.Success.apply
import scala.util.Try
import bazeltools.bsp4bazel.runner.BazelResult.ExitCode

/** Wrapper around BazelRunner for handling `./bazel_rules` rules. Specifically,
  * it handles listing bsp_targets, reading their config, and compiling them
  */
object BspTaskRunner:

  def default(
      workspaceRoot: Path,
      packageRoots: NonEmptyList[BazelLabel],
      logger: Logger,
      wrapper: BazelRunner.BazelWrapper
  ): BspTaskRunner =
    BspTaskRunner(
      workspaceRoot,
      packageRoots,
      logger,
      BazelRunner.default(
        workspaceRoot,
        logger,
        wrapper
      )
    )

  def default(
      workspaceRoot: Path,
      packageRoots: NonEmptyList[BazelLabel],
      logger: Logger
  ): BspTaskRunner =
    BspTaskRunner(
      workspaceRoot,
      packageRoots,
      logger,
      BazelRunner.default(
        workspaceRoot,
        logger
      )
    )

  case class BspTarget(
      id: BuildTargetIdentifier,
      workspaceRoot: Path,
      info: BspTargetInfo
  ):

    private def majorVersion(version: String): String =
      val SemVer = """(\d+)\.(\d+)\.(\d+).*""".r
      version match
        case SemVer("2", minor, patch) =>
          List("2", minor).mkString(".")
        case SemVer("3", _, _) =>
          "3"

    def asBuildTarget: BuildTarget =
      BuildTarget(
        id = id,
        displayName = Some(id.uri.getPath),
        baseDirectory = Some(UriFactory.fileUri(workspaceRoot)),
        tags = List("library"),
        capabilities = BuildTargetCapabilities(true, false, false, false),
        languageIds = List("scala"),
        // TODO
        dependencies = Nil,
        dataKind = Some("scala"),
        Some(
          ScalaBuildTarget(
            scalaOrganization = "org.scala-lang",
            scalaVersion = info.scalaVersion,
            scalaBinaryVersion = majorVersion(info.scalaVersion),
            platform = ScalaPlatform.JVM,
            jars = info.scalaCompileJars.map(p =>
              UriFactory.fileUri(workspaceRoot.resolve(p))
            ),
            jvmBuildTarget = None
          ).asJson
        )
      )

    def asScalaOptionItem: ScalacOptionsItem =
      ScalacOptionsItem(
        target = id,
        // NB: Metals looks for these parameters to be set specifically. They don't really do anything here as
        // semanticdb is instead configured in the Bazel rules.
        options = List(
          s"-Xplugin:${info.semanticdbPluginjar}",
          s"-P:semanticdb:sourceroot:${workspaceRoot}"
        ) ::: info.scalacOptions,
        classpath =
          info.classpath.map(p => UriFactory.fileUri(workspaceRoot.resolve(p))),
        classDirectory =
          UriFactory.fileUri(workspaceRoot.resolve(info.semanticdbTargetRoot))
      )

  case class BspTargetInfo(
      scalaVersion: String,
      scalacOptions: List[String],
      classpath: List[Path],
      scalaCompileJars: List[Path],
      srcs: List[Path],
      targetLabel: BazelLabel,
      semanticdbTargetRoot: Path,
      semanticdbPluginjar: List[Path]
  )

  object BspTargetInfo:
    given pathDecoder: Decoder[Path] = Decoder.decodeString.emap { str =>
      Try(Paths.get(str)).toEither.left.map(_.toString)
    }

    given bspTargetInfoDecoder: Decoder[BspTargetInfo] =
      Decoder.forProduct8(
        "scala_version",
        "scalac_options",
        "classpath",
        "scala_compiler_jars",
        "srcs",
        "target_label",
        "semanticdb_target_root",
        "semanticdb_pluginjar"
      )(BspTargetInfo.apply)

case class BspTaskRunner(
    workspaceRoot: Path,
    packageRoots: NonEmptyList[BazelLabel],
    logger: Logger,
    runner: BazelRunner
):

  private val SupportedRules = Array(
    "scala_library",
    "scala_test"
  )

  private def raiseIfNotOk(result: BazelResult): IO[Unit] =
    result.exitCode match
      case BazelResult.ExitCode.Ok => IO.unit
      case _ =>
        IO.raiseError(
          new Error(
            s"Failed running BSP Bazel task: ${result.debugString}"
          )
        )

  private def buildTargets(
      packageRoot: BazelLabel
  ): IO[List[BuildTargetIdentifier]] =
    for
      buildResult <- runner.build(
        packageRoot,
        (
          "--aspects",
          "@bsp4bazel-rules//:bsp_target_info_aspect.bzl%bsp_target_info_aspect"
        ),
        ("--output_groups", "bsp_output")
      )
      _ <- raiseIfNotOk(buildResult)
      queryResult <- runner.query(
        s"kind(\"${SupportedRules.mkString("|")}\", ${packageRoot.asString})"
      )
      _ <- raiseIfNotOk(queryResult)
    yield queryResult.stdout
      .map(BazelLabel.fromString)
      .collect { case Right(label) =>
        BuildTargetIdentifier.bazel(label)
      }

  def buildTargets: IO[List[BuildTargetIdentifier]] =
    packageRoots.toList.flatTraverse(buildTargets)

  def bspTargets(
      targets: List[BuildTargetIdentifier]
  ): IO[List[BspTaskRunner.BspTarget]] =
    targets.parTraverse(bspTarget)

  def bspTarget(
      target: BuildTargetIdentifier
  ): IO[BspTaskRunner.BspTarget] =
    val bazelLabel = BazelLabel.fromBuildTargetIdentifier(target).toOption.get
    val filePath = workspaceRoot
      .resolve("bazel-bin")
      .resolve(bazelLabel.packagePath.asPath)
      .resolve(s"${bazelLabel.target.get.asString}_bsp_target_info.json")

    for info <- FilesIO.readJson[BspTaskRunner.BspTargetInfo](filePath)
    yield BspTaskRunner.BspTarget(target, workspaceRoot, info)

  private def diagnostics: Stream[IO, FileDiagnostics] =
    FilesIO
      .walk(
        workspaceRoot.resolve("bazel-bin"),
        Some("*.diagnosticsproto"),
        100
      )
      .flatMap { file =>
        Stream
          .eval {
            FilesIO.readBytes(file).map(TargetDiagnostics.parseFrom)
          }
          .flatMap { td =>
            Stream.fromIterator(
              td.diagnostics.iterator,
              100
            )
          }
      }

  def compile(label: BazelLabel): Stream[IO, FileDiagnostics] =
    Stream
      .eval(runner.build(label.allRulesResursive))
      .flatMap { result =>
        result.exitCode match
          case BazelResult.ExitCode.Ok          => Stream.empty
          case BazelResult.ExitCode.BuildFailed => diagnostics
          case _ =>
            Stream.raiseError(
              new Error(
                s"Bsp Compile task failed: ${result.debugString}"
              )
            )
      }

  case class BazelSources(sources: List[String], buildFiles: List[String])

  object BazelSources:
    given Decoder[BazelSources] = Decoder.instance { c =>
      for
        sources <- c.downField("sources").as[List[String]]
        buildFiles <- c.downField("buildFiles").as[List[String]]
      yield BazelSources(sources, buildFiles)
    }