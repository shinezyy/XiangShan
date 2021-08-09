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

package device

import chisel3._
import chisel3.util._
import chipsalliance.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy.AddressSet
import utils._

class FlashHelper extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val ren = Input(Bool())
    val data = Output(UInt(64.W))
    val addr = Input(UInt(32.W))
  }).suggestName("io")

  setInline("FlashHelper.v",
    s"""
       |import "DPI-C" function void flash_read
       |(  
       |  input int addr,
       |  output longint data
       |);
       |
       |module FlashHelper (
       |  input clk,
       |  input [31:0] addr,
       |  input ren,
       |  output reg [63:0] data
       |);
       |
       |  always @(posedge clk) begin
       |    if (ren) flash_read(addr, data);
       |  end
       |
       |endmodule
     """.stripMargin)
}


class AXI4Flash
(
  address: Seq[AddressSet]
)(implicit p: Parameters)
  extends AXI4SlaveModule(address, executable = false)
{

  override lazy val module = new AXI4SlaveModuleImp(this){
    def getOffset(addr: UInt) = addr(15,0)

    val flash = Module(new FlashHelper)
    flash.io.clk := clock
    flash.io.ren := in.ar.fire()
    flash.io.addr := Cat(0.U(16.W), getOffset(raddr))

    in.r.bits.data := flash.io.data
  }
}
