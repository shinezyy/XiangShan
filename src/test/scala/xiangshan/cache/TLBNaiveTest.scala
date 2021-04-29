// package xiangshan.cacheTest

// import org.scalatest._
// import chiseltest._
// import chisel3._
// import chisel3.experimental.BundleLiterals._
// import chisel3.util._
// import org.scalatest.flatspec.AnyFlatSpec
// import org.scalatest.matchers.must.Matchers
// import xiangshan._
// import xiangshan.cache.{PTW, PTWFilter, PTWRepeater}
// import xiangshan.testutils._

// case object TLBTestKey extends Field[Long]

// class TLBTestTop()(implicit p: Parameters) extends LazyModule {
//   val TLB = LazyModule(new TLB)
//   val ram = LazyModule(new AXI4RAM(
//     Seq(AddressSet(0x0L, 0xffffffffffL)),
//     memByte = 128 * 1024 * 1024,
//     useBlackBox = false
//   ))

//   ram.node := TLToAXI4 := TLB.node

//   lazy val module = new LazyModuleImp(this) with HasXSLog {
//     val io = IO(Flipped(TLBTestTopIO))

//     AddSinks()
//   }
// }

// class TLBTest extends AnyFlatSpec with ChiselScalatestTester with Matchers {
//   behavior of "TLB"

//   top.Parameters.set(top.Parameters.debugParameters)

//   val anns = Seq(
//     VerilatorBackendAnnotation,
//     RunFirrtlTransformAnnotation(new PrintModuleName)
//   )

//   it should "run" in {

//     implicit val p = Parameters((site, up, here) => {
//       case TLBTestKey => 0
//     })

//     test(LazyModule(new TLBTestTop()).module)
//       .withAnnotations(annos) { c =>
//         c.clock.step(100)

//         def init() = {
//           c.io.balabala
//         }

//         def sfence() = {
//           c.io.sfence.valid = true.B
//           balab
//           b
//         }
//       }
//   }
// }