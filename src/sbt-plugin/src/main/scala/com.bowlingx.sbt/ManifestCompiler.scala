package com.bowlingx.sbt

import sbt.{File, IO}
import spray.json._

case class WebpackEntry(js: Option[String], css: Option[String])

object WebpackEntryProtocol extends DefaultJsonProtocol {
  implicit val entryFormat = jsonFormat2(WebpackEntry)
}
/**
  * Created by david on 16.01.17.
  */
private[sbt] case class ManifestCompiler(jsonFile:File) {

  private[this] val string = IO.read(jsonFile)
  def generate(): String = {
    import WebpackEntryProtocol._

    val manifest = string.parseJson.convertTo[Map[String, WebpackEntry]]
    val classString = s"""
       |package com.bowlingx.webpack
       |
       |// THIS FILE HAS BEEN AUTO GENERATED by ${this.getClass.toString}
       |
       |object WebpackManifest extends WebpackManifestType {
       |  val entries:Map[String, WebpackEntry] = Map(
       |  ${manifest.map { case (bundle:String, entry:WebpackEntry) =>
            s"""
               |(${"\"" + bundle + "\""} ->
               |  WebpackEntry(${entry.js.map(e => s"Some(${"\"" + e + "\""})").getOrElse("None")},
               |  ${entry.css.map(e => s"Some(${"\"" + e + "\""})").getOrElse("None")}
               | )
               |)
             """.stripMargin
          }.mkString(",")}
       |)
       |  ${manifest.map { case (bundle:String, entry:WebpackEntry) =>
              s"""
                 |
                 |val $bundle = WebpackEntry(
                 |${entry.js.map(e => s"Some(${"\"" + e + "\""})").getOrElse("None")},
                 |${entry.css.map(e => s"Some(${"\"" + e + "\""})").getOrElse("None")}
                 |)
                 |
                 |""".stripMargin
          }.mkString("\n")}
       |}
     """.stripMargin
    classString
  }
}
