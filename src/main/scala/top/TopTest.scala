package top

import chisel3._
import chisel3.util._
import xiangshan._
import utils._
import system._
import chisel3.stage.ChiselGeneratorAnnotation
import chipsalliance.rocketchip.config._
import freechips.rocketchip.diplomacy._
import device.{AXI4Plic, TLTimer}
import firrtl.stage.RunFirrtlTransformAnnotation
import xiangshan.backend.issue._
import xiangshan.backend.exu._
import freechips.rocketchip.util.{ElaborationArtefacts, HasRocketChipStageUtils}

class TestTop()(implicit p: Parameters) extends LazyModule {
  lazy val module = new LazyModuleImp(this) {
  val io = IO(new Bundle {
    val valid = Vec(8, Input(Bool()))
    val by_data = Vec(4, Input(UInt(64.W)))
    val source = Vec(4, Vec(2, Input(UInt(64.W))))
  })

  val bypass = Seq.fill(4)(Module(new BypassNetwork(2, 8, 64, true)))
  val alu = Seq.fill(4)(Module(new AluExeUnit))

  val all_valid = io.valid.drop(4) ++ VecInit(alu.map(_.io.out.valid))
  val all_data = io.by_data ++ VecInit(alu.map(_.io.out.bits.data))
  for (i <- 0 until 4) {
    bypass(i).io.hold := false.B
    bypass(i).io.source := io.source(i)
    for (j <- 0 until 8) {
      bypass(i).io.bypass(j).valid := all_valid(i)
      bypass(i).io.bypass(j).data := all_data(i)
    }
    alu(i).io.redirect.valid := false.B
    alu(i).io.redirect.bits := DontCare
    alu(i).io.flush := false.B
    alu(i).io.fromInt.valid := io.valid(i)
    alu(i).io.fromInt.bits.src := bypass(i).io.target
    alu(i).io.fromInt.bits.uop := DontCare
  }
  }
}

object TestMain extends App with HasRocketChipStageUtils {
  override def main(args: Array[String]): Unit = {
    val (config, firrtlOpts) = ArgParser.parse(args)
    XiangShanStage.execute(firrtlOpts, Seq(
      ChiselGeneratorAnnotation(() => {
        val soc = LazyModule(new TestTop()(config))
        soc.module
      })
    ))
    ElaborationArtefacts.files.foreach{ case (extension, contents) =>
      writeOutputFile("./build", s"XSTop.${extension}", contents())
    }
  }
}
