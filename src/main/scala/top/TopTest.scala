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

class AluPacket extends Bundle {
  val valid = Bool()
  val isRVC = Bool()
  val brType = UInt(2.W)
  val isCall = Bool()
  val isRet = Bool()
  val pred_taken = Bool()
  val ftq_flag = Bool()
  val ftq_value = UInt(6.W)
  val ftq_offset = UInt(4.W)
  val fuOpType = UInt(6.W)
  val rfWen = Bool()
  val fpWen = Bool()
  val flushPipe = Bool()
  val imm = UInt(20.W)
  val pdest = UInt(8.W)
  val roq_flag = Bool()
  val roq_value = UInt(8.W)
}

class AluOutput extends Bundle {
  val valid = Bool()
  val isRVC = Bool()
  val brType = UInt(2.W)
  val isCall = Bool()
  val isRet = Bool()
  val rfWen = Bool()
  val fpWen = Bool()
  val flushPipe = Bool()
  val imm = UInt(20.W)
  val pdest = UInt(8.W)
  val roq_flag = Bool()
  val roq_value = UInt(8.W)
  val data = UInt(64.W)
  val redirectValid = Bool()
  val redirect_roq_flag = Bool()
  val redirect_roq_value = UInt(8.W)
  val redirect_ftq_flag = Bool()
  val redirect_ftq_value = UInt(6.W)
  val redirect_ftq_offset = UInt(4.W)
  val predTaken = Bool()
  val taken = Bool()
  val isMisPred = Bool()
}
class TestTop()(implicit p: Parameters) extends LazyModule {
  lazy val module = new LazyModuleImp(this) {
    val io = IO(new Bundle {
      val valid = Vec(8, Vec(2, Input(UInt(1.W))))
      val by_data = Vec(4, Input(UInt(64.W)))
      val source = Vec(4, Vec(2, Input(UInt(64.W))))
      val uop_in = Vec(4, Input(new AluPacket))
      val alu_out = Vec(4, Output(new AluOutput))
    })

    val bypass = Seq.fill(4)(Module(new BypassNetwork(2, 8, 64, true)))
    val alu = Seq.fill(4)(Module(new AluExeUnit))

    val all_valid = io.valid
    val all_data = io.by_data ++ VecInit(alu.map(_.io.out.bits.data))
    for (i <- 0 until 4) {
      bypass(i).io.hold := false.B
      bypass(i).io.source := io.source(i)
      for (j <- 0 until 8) {
        bypass(i).io.bypass(j).valid := all_valid(j)
        bypass(i).io.bypass(j).data := all_data(j)
      }
      alu(i).io.redirect.valid := false.B
      alu(i).io.redirect.bits := DontCare
      alu(i).io.flush := false.B
      alu(i).io.fromInt.valid := io.uop_in(i).valid
      alu(i).io.fromInt.bits.src(0) := bypass(i).io.target(0)
      alu(i).io.fromInt.bits.src(1) := bypass(i).io.target(1)
      alu(i).io.fromInt.bits.src(2) := DontCare
      alu(i).io.fromInt.bits.uop := DontCare
      alu(i).io.fromInt.bits.uop.cf.pd.isRVC := io.uop_in(i).isRVC
      alu(i).io.fromInt.bits.uop.cf.pd.brType := io.uop_in(i).brType
      alu(i).io.fromInt.bits.uop.cf.pd.isCall := io.uop_in(i).isCall
      alu(i).io.fromInt.bits.uop.cf.pd.isRet := io.uop_in(i).isRet
      alu(i).io.fromInt.bits.uop.cf.pred_taken := io.uop_in(i).pred_taken
      alu(i).io.fromInt.bits.uop.cf.ftqPtr.flag := io.uop_in(i).ftq_flag
      alu(i).io.fromInt.bits.uop.cf.ftqPtr.value := io.uop_in(i).ftq_value
      alu(i).io.fromInt.bits.uop.cf.ftqOffset := io.uop_in(i).ftq_offset
      alu(i).io.fromInt.bits.uop.ctrl.fuOpType := io.uop_in(i).fuOpType
      alu(i).io.fromInt.bits.uop.ctrl.rfWen := io.uop_in(i).rfWen
      alu(i).io.fromInt.bits.uop.ctrl.fpWen := io.uop_in(i).fpWen
      alu(i).io.fromInt.bits.uop.ctrl.flushPipe := io.uop_in(i).flushPipe
      alu(i).io.fromInt.bits.uop.ctrl.imm := io.uop_in(i).imm
      alu(i).io.fromInt.bits.uop.pdest := io.uop_in(i).pdest
      alu(i).io.fromInt.bits.uop.roqIdx.flag := io.uop_in(i).roq_flag
      alu(i).io.fromInt.bits.uop.roqIdx.value := io.uop_in(i).roq_value
      alu(i).io.out.ready := true.B
      io.alu_out(i).valid := alu(i).io.out.valid
      io.alu_out(i).isRVC := alu(i).io.out.bits.uop.cf.pd.isRVC
      io.alu_out(i).brType := alu(i).io.out.bits.uop.cf.pd.brType
      io.alu_out(i).isCall := alu(i).io.out.bits.uop.cf.pd.isCall
      io.alu_out(i).isRet := alu(i).io.out.bits.uop.cf.pd.isRet
      io.alu_out(i).rfWen := alu(i).io.out.bits.uop.ctrl.rfWen
      io.alu_out(i).fpWen := alu(i).io.out.bits.uop.ctrl.fpWen
      io.alu_out(i).flushPipe := alu(i).io.out.bits.uop.ctrl.flushPipe
      io.alu_out(i).imm := alu(i).io.out.bits.uop.ctrl.imm
      io.alu_out(i).pdest := alu(i).io.out.bits.uop.pdest
      io.alu_out(i).roq_flag := alu(i).io.out.bits.uop.roqIdx.flag
      io.alu_out(i).roq_value := alu(i).io.out.bits.uop.roqIdx.value
      io.alu_out(i).data := alu(i).io.out.bits.data
      io.alu_out(i).redirectValid := alu(i).io.out.bits.redirectValid
      io.alu_out(i).redirect_roq_flag := alu(i).io.out.bits.redirect.roqIdx.flag
      io.alu_out(i).redirect_roq_value := alu(i).io.out.bits.redirect.roqIdx.value
      io.alu_out(i).redirect_ftq_flag := alu(i).io.out.bits.redirect.ftqIdx.flag
      io.alu_out(i).redirect_ftq_value := alu(i).io.out.bits.redirect.ftqIdx.value
      io.alu_out(i).redirect_ftq_offset := alu(i).io.out.bits.redirect.ftqOffset
      io.alu_out(i).predTaken := alu(i).io.out.bits.redirect.cfiUpdate.predTaken
      io.alu_out(i).taken := alu(i).io.out.bits.redirect.cfiUpdate.taken
      io.alu_out(i).isMisPred := alu(i).io.out.bits.redirect.cfiUpdate.isMisPred
    }
  }
}

object TestMain extends App with HasRocketChipStageUtils {
  override def main(args: Array[String]): Unit = {
    val (config, firrtlOpts) = ArgParser.parse(args)
    XiangShanStage.execute(firrtlOpts, Seq(
      ChiselGeneratorAnnotation(() => {
        val soc = LazyModule(new TestTop()(config.alterPartial{
          case XSCoreParamsKey => config(SoCParamsKey).cores.head
        }))
        soc.module
      })
    ))
  }
}
