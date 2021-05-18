package xiangshan.backend.exu

import chipsalliance.rocketchip.config.Parameters
import chisel3._
import xiangshan._
import xiangshan.backend.fu.fpu._

class FmacExeUnit(empty: Int)(implicit p: Parameters) extends Exu(FmacExeUnitCfg)
{

  val frm = IO(Input(UInt(3.W)))
  val fma = supportedFunctionUnits.head.asInstanceOf[FMA]

  if (empty < 2) {
  val input = io.fromFp.bits
  val fmaOut = fma.io.out.bits
  val isRVD = !io.fromFp.bits.uop.ctrl.isRVF
  fma.io.in.bits.src := VecInit(Seq(input.src(0), input.src(1), input.src(2)))
  val instr_rm = io.fromFp.bits.uop.ctrl.fpu.rm
  fma.rm := Mux(instr_rm =/= 7.U, instr_rm, frm)

  fma.io.redirectIn := io.redirect
  fma.io.flushIn := io.flush
  fma.io.out.ready := io.out.ready

  io.out.bits.data := box(fma.io.out.bits.data, fma.io.out.bits.uop.ctrl.fpu.typeTagOut)
  io.out.bits.fflags := fma.fflags
  }
  else {
    io := DontCare
    fma.rm := DontCare
    fma.io := DontCare
    io.out.valid := false.B
    io.fromFp.ready := false.B
  }
}
