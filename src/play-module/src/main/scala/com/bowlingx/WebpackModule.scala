package com.bowlingx

import java.net.URL
import java.nio.file.Paths

import com.bowlingx.providers.{EngineProvide, ScriptResources}
import com.bowlingx.webpack._
import play.api.{Configuration, Environment, Logger}
import play.api.inject.{Binding, Module}
import javax.inject.Singleton

import scala.util.{Failure, Success, Try}
import scala.util.matching.Regex

class WebpackModule extends Module {

  val URL_MATCH: Regex = "(https?:\\/\\/)".r

  val logger = Logger(this.getClass)

  override def bindings(environment: Environment, configuration: Configuration): Seq[Binding[_]] = {
    val logger = Logger(this.getClass)

    val manifestOption = configuration.getOptional[String]("webpack.manifestClass").flatMap(className => Try {
      val cons = environment.classLoader.loadClass(className).getDeclaredConstructors
      cons(0).setAccessible(true)
      cons(0).newInstance().asInstanceOf[WebpackManifestType]
    }.toOption)

    if (manifestOption.isEmpty) {
      logger.warn(
        "Could not find webpack manifest class, make sure you setup the `play-react-plugin` correctly."
      )
    }

    val publicToServerEntry = this.mapServerPath(configuration, manifestOption)

    val prependedBundles = configuration.getOptional[Seq[String]]("webpack.prependBundles").map(list =>
      publicToServerEntry.map(entry => entry.entries.filter { case (index, _) =>
        list.contains(index)
      }.toSeq.sortBy { case (index, _) => list.indexOf(index) }
      ).getOrElse(Seq.empty)
    ).getOrElse(Seq.empty)

    val engines = this.createEngines(environment, publicToServerEntry, prependedBundles)

    val webpackManifestBinding = Seq(manifestOption.map { manifest =>
      bind(classOf[WebpackManifestType]).to(manifest).in(classOf[Singleton])
    }).flatten

    engines ++ webpackManifestBinding
  }

  /**
    * Creates Engines, based on the bundles defined in the manifest file
    *
    * @param environment         DI
    * @param publicToServerEntry all entries
    * @param prependedBundles    bundles that should be prepended
    * @return engines
    */
  def createEngines(
                     environment: Environment,
                     publicToServerEntry: Option[WebpackManifestType],
                     prependedBundles: Seq[(String, Either[WebpackEntryType, String])]
                   ): Seq[Binding[Engine]] = {
    val engines = publicToServerEntry.map(manifest => {
      manifest.entries.filter(e => !prependedBundles.contains(e)).map { case (index, entry) =>
        bind(classOf[Engine]).qualifiedWith(index).to(
          new EngineProvide(
            ScriptResources(this.createVendorResources(environment, Some(entry), prependedBundles))
          )
        ).in(classOf[Singleton])
      }.toSeq
    }).getOrElse(Seq.empty)

    // default engine that just contains the prepended entries
    engines :+ bind(classOf[Engine]).to(
      new EngineProvide(ScriptResources(this.createVendorResources(environment, None, prependedBundles)))
    ).in(classOf[Singleton])
  }

  def createVendorResources(
                             environment: Environment,
                             entry: Option[Either[WebpackEntryType, String]],
                             prepends: Seq[(String, Either[WebpackEntryType, String])]
                           ): Seq[URL] = {
    // we watch the real source in develop, this is faster instead of waiting till other watch processes copy the file to resources
    val preSources = prepends.flatMap {
      case (_, Right(sourceEntry)) => Some(this.getFileFromResourcesOrProjectPath(environment, sourceEntry))
      case (_, Left(sourceEntry)) =>
        sourceEntry.js.map(
          r => this.getFileFromResourcesOrProjectPath(environment, r))
    }.flatten
    val entrySource = Seq(entry.flatMap {
      case Left(mapped) => mapped.js.flatMap(r => this.getFileFromResourcesOrProjectPath(environment, r))
      case Right(flat) => this.getFileFromResourcesOrProjectPath(environment, flat)
    }).flatten

    preSources ++ entrySource
  }

  def getFileFromResourcesOrProjectPath(env: Environment, file: String): Option[URL] = {
    env.mode match {
      case play.api.Mode.Dev => env.getExistingFile(file).map(_.toURI.toURL)
      case _ => env.resource(file)
    }
  }

  /**
    * Maps the public server path manifest to the internal resource files
    *
    * @param configuration play configuration
    * @param manifest      original manifest
    * @return
    */
  def mapServerPath(
                     configuration: Configuration,
                     manifest: Option[WebpackManifestType]
                   ): Option[WebpackManifestType] = {
    manifest.map { manifest =>
      val publicPathOption = configuration.getOptional[String]("webpack.publicPath")
      val serverPathOption = configuration.getOptional[String]("webpack.serverPath")
      WebpackManifestContainer((publicPathOption, serverPathOption) match {
        case (Some(publicPath), Some(serverPath)) =>
          manifest.entries.mapValues {
            case Left(entry) =>
              val jsEntries = entry.js.map(path =>
                Paths.get(serverPath, replacePublicPath(path, publicPath)).toString)
              val cssEntries = entry.css.map(path =>
                Paths.get(serverPath, replacePublicPath(path, publicPath)).toString)
              Left(WebpackEntry(jsEntries, cssEntries))
            case Right(entry) => Right(Paths.get(serverPath, replacePublicPath(entry, publicPath)).toString)
          }
        case _ => Map.empty[String, Either[WebpackEntry, String]]
      })
    }
  }

  def replacePublicPath(filePath: String, publicPath: String): String = {
    val actualPath = URL_MATCH.findFirstIn(filePath).map { _ =>
      val actualUrl = new URL(filePath)
      Paths.get(actualUrl.getPath)
    }.getOrElse(Paths.get(filePath))
    Try {
      Paths.get(publicPath).relativize(actualPath).toString
    } match {
      case Success(path) => path
      case Failure(ex) =>
        throw new IllegalArgumentException(
          "Could not convert path's, are you using relative paths in your manifest?", ex
        )
    }
  }
}