/***************************************************************************************
* Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
* Copyright (c) 2020-2021 Peng Cheng Laboratory
*
* XiangShan is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

import os.Path
import mill._
import scalalib._
import $file.firrtl.build
import $file.chisel3.build
import coursier.maven.MavenRepository
import $ivy.`com.lihaoyi::mill-contrib-bloop:0.9.8`

trait CommonModule extends ScalaModule {
  override def scalaVersion = "2.12.10"

  override def scalacOptions = Seq("-Xsource:2.11")

  private val macroParadise = ivy"org.scalamacros:::paradise:2.1.0"

  override def compileIvyDeps = Agg(macroParadise)

  override def scalacPluginIvyDeps = Agg(macroParadise)

  override def repositoriesTask = T.task {
    super.repositoriesTask() ++ Seq(
      MavenRepository("https://oss.sonatype.org/content/repositories/snapshots")
    )
  }

}

object firrtlXS extends firrtl.build.firrtlCrossModule("2.12.13") {
  override def millSourcePath = os.pwd / "firrtl"
}

object chiselSrc extends chisel3.build.chisel3CrossModule("2.12.13") {
  override def millSourcePath = os.pwd / "chisel3"

  def firrtlModule: Option[PublishModule] = Some(firrtlXS)
}

object `api-config-chipsalliance` extends CommonModule {
  override def millSourcePath = super.millSourcePath / "design" / "craft"
}

object hardfloat extends SbtModule with CommonModule {
  override def millSourcePath = os.pwd / "berkeley-hardfloat"
  override def ivyDeps = super.ivyDeps()
  override def moduleDeps = super.moduleDeps ++ Seq(chiselSrc)

  def chisel3Module: Option[PublishModule] = Some(chiselSrc)
}

object `rocket-chip` extends SbtModule with CommonModule {

  override def ivyDeps = super.ivyDeps() ++ Agg(
    ivy"${scalaOrganization()}:scala-reflect:${scalaVersion()}",
    ivy"org.json4s::json4s-jackson:3.6.1"
  )

  object macros extends SbtModule with CommonModule

  override def moduleDeps = super.moduleDeps ++ Seq(
    `api-config-chipsalliance`, macros, hardfloat, chiselSrc
  )

  def chisel3Module: Option[PublishModule] = Some(chiselSrc)

}

object `block-inclusivecache-sifive` extends CommonModule {
  override def ivyDeps = super.ivyDeps()

  override def millSourcePath = super.millSourcePath  / 'design / 'craft / 'inclusivecache

  override def moduleDeps = super.moduleDeps ++ Seq(`rocket-chip`) ++ Seq(chiselSrc)
}

object chiseltest extends CommonModule with SbtModule {
  override def ivyDeps = super.ivyDeps() ++ Agg(
    ivy"edu.berkeley.cs::treadle:1.3.0",
    ivy"org.scalatest::scalatest:3.2.0",
    ivy"com.lihaoyi::utest:0.7.4"
  )
  object test extends Tests {
    def ivyDeps = Agg(ivy"org.scalacheck::scalacheck:1.14.3")
    def testFrameworks = Seq("org.scalatest.tools.Framework")
  }
  override def moduleDeps = super.moduleDeps ++ Seq(chiselSrc)

  def chisel3Module: Option[PublishModule] = Some(chiselSrc)
}

object difftest extends SbtModule with CommonModule {
  override def millSourcePath = os.pwd / "difftest"
  override def moduleDeps = super.moduleDeps ++ Seq(chiselSrc)
}

object fudian extends CommonModule with SbtModule {
  override def moduleDeps = super.moduleDeps ++ Seq(chiselSrc)
}

object XiangShan extends CommonModule with SbtModule {
  override def millSourcePath = millOuterCtx.millSourcePath

  override def forkArgs = Seq("-Xmx64G", "-Xss256m")

  override def ivyDeps = super.ivyDeps()
  override def moduleDeps = super.moduleDeps ++ Seq(
    `rocket-chip`,
    `block-inclusivecache-sifive`,
    chiselSrc,
    chiseltest,
    difftest,
    fudian
  )

  object test extends Tests {

    override def forkArgs = Seq("-Xmx64G", "-Xss256m")

    override def ivyDeps = super.ivyDeps() ++ Agg(
      ivy"org.scalatest::scalatest:3.2.0"
    )

    def testFrameworks = Seq(
      "org.scalatest.tools.Framework"
    )
  }

}
