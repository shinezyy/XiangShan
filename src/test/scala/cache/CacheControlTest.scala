package cache

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import xiangshan.testutils._
import xiangshan.cache._
import xiangshan._
import utils._

class ICacheTesTop extends XSModule{
    val io = IO(new ICacheIO)
    val cache = Module(new ICache)

    io <> cache.io
    val timer = GTimer()
    val logEnable = WireInit(true.B)
    ExcitingUtils.addSource(logEnable, "DISPLAY_LOG_ENABLE")
    ExcitingUtils.addSource(timer, "logTimestamp")

}

class CacheControlTest extends AnyFlatSpec
    with TestConst 
    with ChiselScalatestTester 
    with Matchers 
    with ParallelTestExecution
    with HasPartialDecoupledDriver {

    top.Parameters.set(top.Parameters.debugParameters)
    val enableDebug = top.Parameters.get.envParameters.EnableDebug
    println(s"enabledebug $enableDebug")

    it should  "test ICacheController"in {
        test(new ICacheTesTop){ c =>            
            def sendReq(addr: UInt, mask: UInt){
                c.io.req.valid.poke(true.B)
                c.io.req.bits.addr.poke(addr)
                c.io.req.bits.mask.poke(mask)
            }

            def tlbResp(paddr: UInt){
                c.io.tlb.resp.valid.poke(true.B)
                c.io.tlb.resp.bits.paddr.poke(paddr)
            }

            def memResp(data: UInt, id: UInt){
                c.io.mem_grant.valid.poke(true.B)
                c.io.mem_grant.bits.data.poke(data)
                c.io.mem_grant.bits.id.poke(id)
            }

            //def cacheControl(op: UInt, way)
            val c_idle :: c_req :: c_tlb :: c_mem :: c_resp :: Nil = Enum(5)
            var state = c_idle

            val vpc = 60000000.U
            val ppc = 60000000.U
            val mask = 1.U

            c.io.mem_acquire.ready.poke(true.B)
            state = c_req

            while(state != c_idle){
                if(state == c_req){
                    println("[TEST] send Req")
                    sendReq(vpc,mask)
                    state = c_tlb
                    c.clock.step()
                } 
                else if(state == c_tlb){
                    println("[TEST] tlb Resp")
                    tlbResp(ppc)
                    state = c_mem
                    c.clock.step(10)
                }
                else if(state == c_mem){
                    val cacheMemReq = c.io.mem_acquire.valid.peek().litToBoolean
                    val cacheMemId  = c.io.mem_acquire.bits.id.peek()
                    c.clock.step(4)
                    while(!cacheMemReq){
                        //println("[TEST] wait for mem acquire")
                    }
                    state = c_resp 
                    println("[TEST] send mem response")
                    memResp(0.U, cacheMemId)
                    c.clock.step()
                }
                else if(state == c_resp){
                    println("[TEST] receive cache response")
                    state = c_idle
                }
            }
        }
    }
}