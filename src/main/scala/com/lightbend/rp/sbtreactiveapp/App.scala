/*
 * Copyright 2017 Lightbend, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.lightbend.rp.sbtreactiveapp

import com.typesafe.sbt.packager.Keys.dockerUsername
import sbt._
import sbt.Resolver.bintrayRepo
import scala.collection.immutable.Seq
import scala.collection.JavaConverters._

import Keys._

sealed trait App extends SbtReactiveAppKeys {
  private def libIsPublished(scalaVersion: String) =
    SemVer
      .parse(scalaVersion)
      .fold(false) { case (major, minor, _, _) => major >= 2 && minor >= 11 }

  private def lib(
    scalaVersion: String,
    nameAndCross: (String, Boolean),
    version: String,
    filter: Boolean): Seq[ModuleID] =
    if (filter && nameAndCross._2 && libIsPublished(scalaVersion))
      Seq("com.lightbend.rp" %% nameAndCross._1 % version)
    else if (filter && libIsPublished(scalaVersion))
      Seq("com.lightbend.rp" % nameAndCross._1 % version)
    else
      Seq.empty

  def applicationType: String

  def projectSettings: Seq[Setting[_]] = Vector(
    appName := name.value,
    appType := applicationType,
    cpu := None,
    diskSpace := None,
    memory := None,
    volumes := Map.empty,
    privileged := false,
    healthCheck := None,
    readinessCheck := None,
    environmentVariables := Map.empty,
    startScriptLocation := Some("/rp-start"),
    secrets := Set.empty,
    reactiveLibVersion := "0.3.4",
    reactiveLibAkkaClusterBootstrapProject := "reactive-lib-akka-cluster-bootstrap" -> true,
    reactiveLibCommonProject := "reactive-lib-common" -> true,
    reactiveLibPlayHttpBindingProject := "reactive-lib-play-http-binding" -> true,
    reactiveLibSecretsProject := "reactive-lib-secrets" -> true,
    reactiveLibServiceDiscoveryProject := "reactive-lib-service-discovery" -> true,
    enableAkkaClusterBootstrap := Some(false),
    enableCommon := true,
    enablePlayHttpBinding := false,
    enableSecrets := None,
    // TODO: service discovery must be enabled if Akka Cluster bootstrap is enabled.
    // Because `enableServiceDiscovery` is a setting, while `akkaClusterBootstrapEndpointName` is a task, we can't
    // simply evaluate the task value.
    // I will need to investigate why `akkaClusterBootstrapEnabled` is a task, not a setting.
    enableServiceDiscovery := false,

    prependRpConf := Some("application.conf"),

    akkaClusterBootstrapEndpointName := "akka-remote",
    akkaClusterBootstrapManagementEndpointName := "akka-mgmt-http",
    akkaClusterBootstrapEnabled := false,

    httpIngressHosts := Seq.empty,

    httpIngressPaths := Seq.empty,

    httpIngressPorts := Seq(80, 443),

    secretsEnabled :=
      enableSecrets.value.getOrElse(secrets.value.nonEmpty),

    unmanagedResources in Compile := {
      def annotate(config: String) =
        s"""|# Generated by sbt-reactive-app. To disable this, set the `prependRpConf` SBT key to `None`.
            |
            |$config""".stripMargin

      def withHeader(comment: String, config: String) =
        s"""|# $comment
            |
            |$config""".stripMargin

      val base = (unmanagedResources in Compile).value
      val baseDest = (target in Compile).value

      val dependencyClassLoader =
        new java.net.URLClassLoader((dependencyClasspath in Compile).value.files.map(_.toURI.toURL).toArray)

      val configs =
        dependencyClassLoader
          .findResources(ToolingConfig)
          .asScala

      if (configs.nonEmpty) {
        val mergedConfig =
          configs
            .foldLeft(Seq.empty[String]) {
              case (accum, conf) =>
                accum :+ withHeader(conf.toString, IO.readLinesURL(conf).mkString(IO.Newline))
            }
            .mkString(IO.Newline)

        prependRpConf
          .value
          .map { conf =>
            val dest = baseDest / LocalApplicationConfig

            val existingFile = base.find(_.name == conf)

            existingFile match {
              case None =>
                IO.write(dest, annotate(mergedConfig))
              case Some(f) =>
                IO.write(dest, annotate(mergedConfig + IO.Newline + withHeader(f.toURI.toString, IO.read(f))))
            }

            base :+ dest
          }
          .getOrElse(base)
      } else {
        base
      }
    },

    allDependencies := {
      val bootstrapEnabled = enableAkkaClusterBootstrap.value.getOrElse(akkaClusterBootstrapEnabled.value)

      val bootstrapDependencies =
        lib(scalaVersion.value, reactiveLibAkkaClusterBootstrapProject.value, reactiveLibVersion.value, bootstrapEnabled)

      allDependencies.value ++ bootstrapDependencies
    },

    endpoints := {
      val endpointName = akkaClusterBootstrapEndpointName.value
      val managementEndpointName = akkaClusterBootstrapManagementEndpointName.value
      val bootstrapEnabled = enableAkkaClusterBootstrap.value.getOrElse(akkaClusterBootstrapEnabled.value)

      endpoints.?.value.getOrElse(Seq.empty) ++ {
        if (bootstrapEnabled)
          Seq(TcpEndpoint(endpointName, 0), TcpEndpoint(managementEndpointName, 0))
        else
          Seq.empty
      }
    },

    // This repository is required to resolve Akka DNS dependency hosted at https://bintray.com/hajile/maven/akka-dns
    // Akka DNS is a transitive dependencies from reactive-lib service discovery project which is added as dependency
    // below.
    // TODO: the proper way to do this is to detect if service locator is enabled, including when cluster is enabled.
    // We still have problem setting enableServiceDiscovery := true if akkaClusterBootstrapEnabled task is set to true
    // The workaround is to add the resolvers at all times.
    resolvers += bintrayRepo("hajile", "maven"),

    libraryDependencies ++=
      lib(scalaVersion.value, reactiveLibCommonProject.value, reactiveLibVersion.value, filter = true),

    libraryDependencies ++=
      lib(scalaVersion.value, reactiveLibPlayHttpBindingProject.value, reactiveLibVersion.value, enablePlayHttpBinding.value),

    libraryDependencies ++=
      lib(scalaVersion.value, reactiveLibSecretsProject.value, reactiveLibVersion.value, enableSecrets.value.getOrElse(secrets.value.nonEmpty)),

    libraryDependencies ++=
      lib(scalaVersion.value, reactiveLibServiceDiscoveryProject.value, reactiveLibVersion.value, enableServiceDiscovery.value),

    dockerUsername := Some(App.normalizeName((name in LocalRootProject).value)))

}

sealed trait LagomApp extends App {
  val applicationType: String = "lagom"

  val apiTools = config("api-tools").hide

  override def projectSettings: Seq[Setting[_]] = {
    // managedClasspath in "api-tools" contains the api tools library dependencies
    // fullClasspath contains the Lagom services, Lagom framework and all its dependencies

    super.projectSettings ++ Vector(
      // For naming Lagom services, we take this overall approach:
      // Calculate the endpoints (lagomRawEndpoints) and make this the "appName"
      // Then, rename the first endpoint (which is the Lagom service itself) to "lagom-http-api" which the
      // service discovery module understands via convention.

      appName := lagomRawEndpoints.value.headOption.map(_.name).getOrElse(name.value),

      enableServiceDiscovery := true,
      enablePlayHttpBinding := true,
      enableAkkaClusterBootstrap := None,

      akkaClusterBootstrapEnabled :=
        magic.Lagom.hasCluster(libraryDependencies.value.toVector),

      ivyConfigurations += apiTools,

      managedClasspath in apiTools :=
        Classpaths.managedJars(apiTools, (classpathTypes in apiTools).value, update.value),

      libraryDependencies ++= magic.Lagom.component("api-tools").toVector.map(_ % apiTools),

      // Note: Play & Lagom need their endpoints defined first (see play-http-binding)

      lagomRawEndpoints := {
        val ingressPorts = httpIngressPorts.value
        val ingressHosts = httpIngressHosts.value
        val ingressPaths = httpIngressPaths.value
        val endpointName = name.value

        val magicEndpoints =
          magic.Lagom.endpoints(
            ((managedClasspath in apiTools).value ++ (fullClasspath in Compile).value).toVector,
            scalaInstance.value.loader,
            ingressPorts.toVector,
            ingressHosts.toVector,
            ingressPaths.toVector)
            .getOrElse(Seq.empty)

        // If we don't have any magic endpoints, we want to explicitly add one for "/" as we are
        // effectively a Play endpoint then.
        val autoEndpoints =
          if (magicEndpoints.nonEmpty)
            magicEndpoints
          else
            Vector(HttpEndpoint(endpointName, 0, HttpIngress(ingressPorts, ingressHosts, Vector("/"))))

        autoEndpoints
      },

      endpoints := {
        lagomRawEndpoints.value.zipWithIndex.map {
          case (e, 0) => e.withName("lagom-http-api")
          case (e, _) => e
        }
      } ++ endpoints.value)
  }
}

case object LagomJavaApp extends LagomApp {
  override def projectSettings: Seq[Setting[_]] =
    super.projectSettings ++ magic.Lagom
      .version
      .toVector
      .map(v =>
        reactiveLibServiceDiscoveryProject := s"reactive-lib-service-discovery-lagom${SemVer.formatMajorMinor(v)}-java" -> false)
}

case object LagomScalaApp extends LagomApp {
  override def projectSettings: Seq[Setting[_]] =
    super.projectSettings ++ magic.Lagom
      .version
      .toVector
      .map(v => reactiveLibServiceDiscoveryProject := s"reactive-lib-service-discovery-lagom${SemVer.formatMajorMinor(v)}-scala" -> true)
}

case object PlayApp extends App {
  val applicationType: String = "play"

  override def projectSettings: Seq[Setting[_]] = {
    super.projectSettings ++ Vector(
      // Note: Play & Lagom need their endpoints defined first (see play-http-binding)

      httpIngressPaths := Vector("/"),

      enablePlayHttpBinding := true,

      endpoints :=
        HttpEndpoint(name.value, 0, HttpIngress(httpIngressPorts.value, httpIngressHosts.value, httpIngressPaths.value)) +:
        endpoints.value)
  }
}

case object BasicApp extends App {
  val applicationType: String = "basic"
}

object App {
  private val ValidNameChars =
    (('0' to '9') ++ ('A' to 'Z') ++ ('a' to 'z') ++ Seq('-')).toSet

  private val NameTrimChars = Set('-')

  private[sbtreactiveapp] def normalizeName(name: String): String =
    name
      .map(c => if (ValidNameChars.contains(c)) c else '-')
      .dropWhile(NameTrimChars.contains)
      .reverse
      .dropWhile(NameTrimChars.contains)
      .reverse
      .toLowerCase
}

private object SemVer {
  def formatMajorMinor(version: String): String = version.filterNot(_ == '.').take(2)

  def parse(version: String): Option[(Int, Int, Int, Option[String])] = {
    val parts = version.split("\\.", 3)

    if (parts.length == 3 &&
      parts(0).forall(_.isDigit) &&
      parts(1).forall(_.isDigit) &&
      parts(2).takeWhile(_ != '-').forall(_.isDigit)) {
      val major = parts(0).toInt
      val minor = parts(1).toInt
      val patchParts = parts(2).split("-", 2)

      val (patch, label) =
        if (patchParts.length == 2)
          (patchParts(0).toInt, Some(patchParts(1)))
        else
          (parts(2).toInt, None)

      Some((major, minor, patch, label))
    } else {
      None
    }
  }
}
