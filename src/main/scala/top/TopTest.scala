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
import xiangshan.cache._

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

class LoadPacket extends Bundle {
  val valid = Bool()
  val isRVC = Bool()
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
  val lq_flag = Bool()
  val lq_value = UInt(6.W)
  val sq_flag = Bool()
  val sq_value = UInt(6.W)
}

class LoadS0Output extends Bundle {
  val valid = Bool()
  val paddr = UInt(40.W)
  val miss = Bool()
  val mmio = Bool()
  val excp = new Bundle {
    val pf = new Bundle {
      val ld = Bool()
      val st = Bool()
      val instr = Bool()
    }
    val af = new Bundle {
      val ld = Bool()
      val st = Bool()
      val instr = Bool()
    }
  }
}

class FakeLoadS0()(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val in = Input(new LoadPacket)
    val src = Input(UInt(64.W))
    val out = Output(new LoadS0Output)
    val tlb = new BlockTlbRequestIO
  })
  val VAddrBits = 39
  val imm12 = WireInit(io.in.imm(11,0))
  val s0_vaddr_lo = io.src(11,0) + Cat(0.U(1.W), imm12)
  val s0_vaddr_hi = Mux(s0_vaddr_lo(12),
    Mux(imm12(11), io.src(VAddrBits-1, 12), io.src(VAddrBits-1, 12)+1.U),
    Mux(imm12(11), io.src(VAddrBits-1, 12)+SignExt(1.U, VAddrBits-12), io.src(VAddrBits-1, 12)),
  )
  val s0_vaddr = Cat(s0_vaddr_hi, s0_vaddr_lo(11,0))
  // query DTLB
  io.tlb.req.valid := io.in.valid
  io.tlb.req.bits.vaddr := s0_vaddr
  io.tlb.req.bits.cmd := LFSR64()
  io.tlb.req.bits.roqIdx.flag := io.in.roq_flag
  io.tlb.req.bits.roqIdx.value := io.in.roq_value
  io.tlb.req.bits.debug.pc := DontCare
  io.tlb.req.bits.debug.isFirstIssue := DontCare

  io.tlb.resp.ready := true.B
  io.out.valid := io.in.valid && !io.tlb.resp.bits.miss
  io.out.paddr := io.tlb.resp.bits.paddr
  io.out.miss := io.tlb.resp.bits.miss
  io.out.mmio := io.tlb.resp.bits.mmio
  io.out.excp := io.tlb.resp.bits.excp
}

class TestTop()(implicit p: Parameters) extends LazyModule {
  lazy val module = new LazyModuleImp(this) {
    val io = IO(new Bundle {
      val valid = Vec(8, Vec(8, Vec(2, Input(UInt(1.W)))))
      val by_data = Vec(4, Input(UInt(64.W)))
      val alu_source = Vec(4, Vec(2, Input(UInt(64.W))))
      val alu_in = Vec(4, Input(new AluPacket))
      val alu_out = Vec(4, Output(new AluOutput))
      val load_source = Vec(4, Input(UInt(64.W)))
      val load_in = Vec(4, Input(new LoadPacket))
      val load_out = Vec(4, Output(new LoadS0Output))
      val sfence = Input(new SfenceBundle)
      val csr = Input(new TlbCsrBundle)
      val ptw_req_bits = Output(new PtwReq)
      val ptw_req_ready = Input(Bool())
      val ptw_resp_valid = Input(Bool())
      val ptw_resp_bits = Input(new PtwResp)
    })

    val alu_bypass = Seq.fill(4)(Module(new BypassNetwork(2, 8, 64, true)))
    val load_bypass = Seq.fill(4)(Module(new BypassNetwork(1, 8, 64, true)))
    val alu = Seq.fill(4)(Module(new AluExeUnit))
    val load = Seq.fill(4)(Module(new FakeLoadS0))
    val tlb = Module(new TLB(4, false))
    tlb.io.sfence <> io.sfence
    tlb.io.csr <> io.csr
    tlb.io.ptw.req.map(_.ready := io.ptw_req_ready)
    io.ptw_req_bits := tlb.io.ptw.req(0).bits
    tlb.io.ptw.resp.valid := io.ptw_resp_valid
    tlb.io.ptw.resp.bits := io.ptw_resp_bits

    val all_valid = io.valid
    val all_data = io.by_data ++ VecInit(alu.map(_.io.out.bits.data))
    for (i <- 0 until 4) {
      alu_bypass(i).io.hold := false.B
      alu_bypass(i).io.source := io.alu_source(i)
      for (j <- 0 until 8) {
        alu_bypass(i).io.bypass(j).valid := all_valid(i)(j)
        alu_bypass(i).io.bypass(j).data := all_data(j)
      }
      alu(i).io.redirect.valid := false.B
      alu(i).io.redirect.bits := DontCare
      alu(i).io.flush := false.B
      alu(i).io.fromInt.valid := io.alu_in(i).valid
      alu(i).io.fromInt.bits.src(0) := alu_bypass(i).io.target(0)
      alu(i).io.fromInt.bits.src(1) := alu_bypass(i).io.target(1)
      alu(i).io.fromInt.bits.src(2) := DontCare
      alu(i).io.fromInt.bits.uop := DontCare
      alu(i).io.fromInt.bits.uop.cf.pd.isRVC := io.alu_in(i).isRVC
      alu(i).io.fromInt.bits.uop.cf.pd.brType := io.alu_in(i).brType
      alu(i).io.fromInt.bits.uop.cf.pd.isCall := io.alu_in(i).isCall
      alu(i).io.fromInt.bits.uop.cf.pd.isRet := io.alu_in(i).isRet
      alu(i).io.fromInt.bits.uop.cf.pred_taken := io.alu_in(i).pred_taken
      alu(i).io.fromInt.bits.uop.cf.ftqPtr.flag := io.alu_in(i).ftq_flag
      alu(i).io.fromInt.bits.uop.cf.ftqPtr.value := io.alu_in(i).ftq_value
      alu(i).io.fromInt.bits.uop.cf.ftqOffset := io.alu_in(i).ftq_offset
      alu(i).io.fromInt.bits.uop.ctrl.fuOpType := io.alu_in(i).fuOpType
      alu(i).io.fromInt.bits.uop.ctrl.rfWen := io.alu_in(i).rfWen
      alu(i).io.fromInt.bits.uop.ctrl.fpWen := io.alu_in(i).fpWen
      alu(i).io.fromInt.bits.uop.ctrl.flushPipe := io.alu_in(i).flushPipe
      alu(i).io.fromInt.bits.uop.ctrl.imm := io.alu_in(i).imm
      alu(i).io.fromInt.bits.uop.pdest := io.alu_in(i).pdest
      alu(i).io.fromInt.bits.uop.roqIdx.flag := io.alu_in(i).roq_flag
      alu(i).io.fromInt.bits.uop.roqIdx.value := io.alu_in(i).roq_value
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
    for (i <- 0 until 4) {
      load_bypass(i).io.hold := false.B
      load_bypass(i).io.source := VecInit(io.load_source(i))
      for (j <- 0 until 8) {
        load_bypass(i).io.bypass(j).valid := VecInit(all_valid(i+4)(j)(0))
        load_bypass(i).io.bypass(j).data := all_data(j)
      }
      load(i).io.src := load_bypass(i).io.target(0)
      load(i).io.in := io.load_in(i)
      io.load_out(i) := load(i).io.out
      tlb.io.requestor(i) <> load(i).io.tlb
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
