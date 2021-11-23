package lms.transformation.tensor

import scala.annotation.implicitNotFound
import scala.collection._

import lms.core._
import lms.core.stub._
import lms.collection.mutable._
import lms.macros.SourceContext
import lms.thirdparty.array_computation.{ArrayCPUTypeLess, CUDATypeLess, CUBLASTypeLess}

import Backend._

abstract class FusedTensorSimplify extends Transformer {

  import BaseTypeLess._
  import PrimitiveTypeLess._
  import ArrayTypeLess._
  import ArrayCPUTypeLess._
  import FusedTensorTypeLess._
  import PrimitiveTypeLess._

  val tensors = new mutable.HashMap[Backend.Sym, (Node, List[Backend.Sym], Seq[Node])]

  override def transform(n: Node): Backend.Exp = n match {
    
    case Node(s, "tensor_add", (x:Backend.Sym)::(y:Backend.Sym)::_, _) =>
      implicit val pos = Adapter.oldSourceMap(s)
      val a = new TENSOR(transform(x), useOldMetadata = true)
      val b = new TENSOR(transform(y), useOldMetadata = true)
      val t = TENSOR(10)(i => (
        a.apply(INT(i).x) + b.apply(INT(i).x)).x)
      t.x
    case Node(s, "tensor_minus", (x:Backend.Sym)::(y:Backend.Sym)::_, _) =>
      implicit val pos = Adapter.oldSourceMap(s)
      val a = new TENSOR(transform(x), useOldMetadata = true)
      val b = new TENSOR(transform(y), useOldMetadata = true)
      val t = TENSOR(10)(i => (
        a.apply(INT(i).x) - b.apply(INT(i).x)).x)
      t.x
    
    case Node(s, "tensor_tanh", (x:Backend.Sym)::_, _) =>
      implicit val pos = Adapter.oldSourceMap(s)
      val t = new TENSOR(transform(x), useOldMetadata = true)
      val res = TENSOR(10)(i => t.apply(INT(i).x).tanh().x)
      res.x
    case Node(s, "tensor_relu", (x:Backend.Sym)::_, _) =>
      implicit val pos = Adapter.oldSourceMap(s)
      val t = new TENSOR(x, useOldMetadata = true)
      val res = TENSOR(t.size)(i => {
        // IF(c: BOOL)(a: => TOP)(b: => TOP)
        (IF(INT(i) < INT(0))(INT(0))(INT(i))).x
      })
      res.x
    case _ => super.transform(n)
  }

  override def transform(graph: Graph): Graph = {
    assert (g == null)
    g = new GraphBuilderOpt()
    Adapter.g = g
    try {
      super.transform(graph)
    } finally {
      g = null; Adapter.g = null
    }
  }
}
