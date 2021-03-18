package xiangshan.backend.decode

import chisel3._
import chisel3.util._
import xiangshan._
import utils._
import xiangshan.mem.{LqPtr, SqPtr}
import xiangshan.backend.roq.RoqPtr

// store set load violation predictor
// See "Memory Dependence Prediction using Store Sets" for details

// Store Set Identifier Table Entry
class SSITEntry extends XSBundle {
  val valid = Bool()
  val isload = Bool()
  val ssid = UInt(SSIDWidth.W) // store set identifier
}

// Store Set Identifier Table
class SSIT extends XSModule {
  val io = IO(new Bundle {
    val raddr = Vec(DecodeWidth, Input(UInt(MemPredPCWidth.W))) // xor hashed decode pc(VaddrBits-1, 1)
    val rdata = Vec(DecodeWidth, Output(new SSITEntry))
    val update = Input(new MemPredUpdateReq) // RegNext should be added outside
    val csrCtrl = Input(new CustomCSRCtrlIO)
  })

  // TODO: use MemTemplate
  val valid = RegInit(VecInit(Seq.fill(SSITSize)(false.B)))
  val isload = Reg(Vec(SSITSize, Bool()))
  val ssid = Reg(Vec(SSITSize, UInt(SSIDWidth.W)))

  val resetCounter = RegInit(0.U(ResetTimeMax2Pow.W))
  resetCounter := resetCounter + 1.U

  // read SSIT in decode stage
  for (i <- 0 until DecodeWidth) {
    // io.rdata(i) := (data(io.raddr(i))(1) || io.csrCtrl.no_spec_load) && !io.csrCtrl.lvpred_disable
    io.rdata(i).valid := valid(io.raddr(i))
    io.rdata(i).isload := isload(io.raddr(i))
    io.rdata(i).ssid := ssid(io.raddr(i))
  }

  // update SSIT if load violation redirect is detected

  // update stage -1
  // when io.update.valid, we should RegNext() it for at least 1 cycle
  // outside of SSIT.

  // update stage 0
  // RegNext(io.update) while reading SSIT entry for necessary information
  val memPredUpdateReqValid = RegNext(io.update.valid)
  val memPredUpdateReqReg = RegEnable(io.update, enable = io.update.valid)
  // load has already been assigned with a store set
  val loadAssigned = RegNext(valid(io.update.ldpc))
  val loadOldSSID = RegNext(ssid(io.update.ldpc))
  // store has already been assigned with a store set
  val storeAssigned = RegNext(valid(io.update.stpc))
  val storeOldSSID = RegNext(ssid(io.update.stpc))
  // both the load and the store have already been assigned store sets
  // but load's store set ID is smaller
  val loadIsWinner = RegNext(io.update.ldpc < io.update.stpc)
  val winnerSSID = Mux(loadIsWinner, loadOldSSID, storeOldSSID)

  // for now we just use lowest bits of ldpc as store set id
  val ssidAllocate = memPredUpdateReqReg.ldpc(SSIDWidth-1, 0)

  // update stage 1
  when(memPredUpdateReqValid){
    switch (Cat(loadAssigned, storeAssigned)) {
      // 1. "If neither the load nor the store has been assigned a store set, 
      // one is allocated and assigned to both instructions."
      is ("b00".U(2.W)) {
        valid(memPredUpdateReqReg.ldpc) := true.B
        isload(memPredUpdateReqReg.ldpc) := true.B
        ssid(memPredUpdateReqReg.ldpc) := ssidAllocate
        valid(memPredUpdateReqReg.stpc) := true.B
        isload(memPredUpdateReqReg.stpc) := false.B
        ssid(memPredUpdateReqReg.stpc) := ssidAllocate
      }
      // 2. "If the load has been assigned a store set, but the store has not, 
      // the store is assigned the load’s store set."
      is ("b10".U(2.W)) {
        valid(memPredUpdateReqReg.stpc) := true.B
        isload(memPredUpdateReqReg.stpc) := false.B
        ssid(memPredUpdateReqReg.stpc) := loadOldSSID
      }
      // 3. "If the store has been assigned a store set, but the load has not, 
      // the load is assigned the store’s store set."
      is ("b01".U(2.W)) {
        valid(memPredUpdateReqReg.ldpc) := true.B
        isload(memPredUpdateReqReg.ldpc) := true.B
        ssid(memPredUpdateReqReg.ldpc) := storeOldSSID
      }
      // 4. "If both the load and the store have already been assigned store sets, 
      // one of the two store sets is declared the "winner". 
      // The instruction belonging to the loser’s store set is assigned the winner’s store set."
      is ("b11".U(2.W)) {
        valid(memPredUpdateReqReg.ldpc) := true.B
        isload(memPredUpdateReqReg.ldpc) := true.B
        ssid(memPredUpdateReqReg.ldpc) := winnerSSID
        valid(memPredUpdateReqReg.stpc) := true.B
        isload(memPredUpdateReqReg.stpc) := false.B
        ssid(memPredUpdateReqReg.stpc) := winnerSSID
      }
    }
  }

  XSPerf("ssit_update_lxsx", memPredUpdateReqValid && !loadAssigned && !storeAssigned)
  XSPerf("ssit_update_lysx", memPredUpdateReqValid && loadAssigned && !storeAssigned)
  XSPerf("ssit_update_lxsy", memPredUpdateReqValid && !loadAssigned && storeAssigned)
  XSPerf("ssit_update_lysy", memPredUpdateReqValid && loadAssigned && storeAssigned)

  // reset period: ResetTimeMax2Pow
  when(resetCounter(ResetTimeMax2Pow-1, ResetTimeMin2Pow)(RegNext(io.csrCtrl.waittable_timeout))) {
    for (j <- 0 until SSITSize) {
      valid(j) := 0.U
    }
    resetCounter:= 0.U
  }

  // debug
  for (i <- 0 until StorePipelineWidth) {
    when (memPredUpdateReqReg.valid) {
      XSDebug("%d: SSIT update: load pc %x store pc %x\n", GTimer(), memPredUpdateReqReg.ldpc, memPredUpdateReqReg.stpc)
      XSDebug("%d: SSIT update: load valid %b ssid %x  store valid %b ssid %x\n", GTimer(), loadAssigned, loadOldSSID, storeAssigned,storeOldSSID)
    }
  }
}


// Last Fetched Store Table Entry
class LFSTEntry extends XSBundle  {
  val valid = Bool()
  val sqIdx = new SqPtr
  val roqIdx = new RoqPtr
}

class DispatchToLFST extends XSBundle  {
  val sqIdx = new SqPtr
  val roqIdx = new RoqPtr
  val ssid = UInt(SSIDWidth.W)
}

class LookupLFST extends XSBundle  {
  val raddr = Vec(DecodeWidth, Input(UInt(MemPredPCWidth.W))) // xor hashed decode pc(VaddrBits-1, 1)
  val ren = Vec(DecodeWidth, Input(Bool())) // ren iff uop.cf.storeSetHit
  val rdata = Vec(DecodeWidth, Output(Bool()))
}

// Last Fetched Store Table
class LFST extends XSModule  {
  val io = IO(new Bundle {
    val lookup = new LookupLFST
    // val update = Input(new MemPredUpdateReq) // RegNext should be added outside
    // when redirect, mark canceled store as invalid
    val redirect = Input(Valid(new Redirect))
    val flush = Input(Bool())
    // when store is dispatched, mark it as valid
    val dispatch = Vec(RenameWidth, Flipped(Valid(new DispatchToLFST)))
    // when store issued, mark store as invalid
    val storeIssue = Vec(exuParameters.StuCnt, Flipped(Valid(new ExuInput)))
    val csrCtrl = Input(new CustomCSRCtrlIO)
  })

  // TODO: use MemTemplate
  val valid = RegInit(VecInit(Seq.fill(LFSTSize)(false.B)))
  val sqIdx = Reg(Vec(LFSTSize, new SqPtr))
  val roqIdx = Reg(Vec(LFSTSize, new RoqPtr))

  // read LFST in rename stage
  for (i <- 0 until DecodeWidth) {
    io.lookup.rdata(i) := (valid(io.lookup.raddr(i)) && io.lookup.ren(i) || 
      io.csrCtrl.no_spec_load) && !io.csrCtrl.lvpred_disable
  }

  // when store is issued, mark it as invalid
  (0 until exuParameters.StuCnt).map(i => {
    when(io.storeIssue(i).valid){
      valid(io.storeIssue(i).bits.uop.cf.ssid) := false.B
    }
  })

  // when store is dispatched, mark it as valid
  (0 until RenameWidth).map(i => {
    when(io.dispatch(i).valid){
      val waddr = io.dispatch(i).bits.ssid
      valid(waddr) := true.B
      sqIdx(waddr) := io.dispatch(i).bits.sqIdx
      roqIdx(waddr) := io.dispatch(i).bits.roqIdx
    }
  })

  // when redirect, cancel store influenced
  (0 until LFSTSize).map(i => {
    when(roqIdx(i).needFlush(io.redirect, io.flush)){
      valid(i) := false.B
    }
  })
}