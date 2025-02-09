package lms.transformation.tensor

import scala.annotation.implicitNotFound
import scala.collection._

import lms.core._
import lms.core.stub._
import lms.collection.mutable._
import lms.macros.SourceContext
import lms.thirdparty.array_computation.{ArrayCPUTypeLess, CUDATypeLess, CUBLASTypeLess}
import lms.transformation.util.DataStructure

import Backend._


class DimNameAnalysis extends Traverser {
  import FixedSizeDistributedTensorTypeLess._
  import DataStructure._

  var dim_names: USets[Dim] = null

  override def apply(g: Graph): Unit = {
    val oldDefsCache = Adapter.oldDefsCache
    val oldTypeMap = Adapter.oldTypeMap
    val oldSourceMap = Adapter.oldSourceMap
    Adapter.oldDefsCache = g.globalDefsCache
    Adapter.oldTypeMap = Adapter.typeMap
    Adapter.oldSourceMap = Adapter.sourceMap
    val all_dims = scala.collection.mutable.ArrayBuffer[Dim]()
    g.nodes.foreach(n => n match {
      case Node(s, op, _, _) if op.startsWith("tensor_") =>
        val resultType = (new TENSOR(s, useOldMetadata = true)).resultType
        all_dims ++= resultType.shape.map(_.dim)
      case Node(s, op, _, _) if op.startsWith("tensors_") =>
        val resultTypes = (new TENSORS(s, useOldMetadata = true)).resultTypes
        for (resultType <- resultTypes)
          all_dims ++= resultType.shape.map(_.dim)
      case _ => ()
    })
    dim_names = new USets[Dim](all_dims.toList)

    super.apply(g)
    Adapter.oldDefsCache = oldDefsCache
    Adapter.oldTypeMap = oldTypeMap
    Adapter.oldSourceMap = oldSourceMap
  }

  override def traverse(n: Node): Unit = {
    mergable_dims(n).foreach { case (d0, d1) => dim_names.merge(d0, d1) }
    super.traverse(n)
  }
}

abstract class DistributeTensorDimName extends Transformer {
  import DataStructure._
  override val name = "DistributedTensorDimName"

  import PrimitiveTypeLess._
  import ArrayTypeLess._
  import ArrayCPUTypeLess._
  import FixedSizeDistributedTensorTypeLess._
  import CUDATypeLess._
  import CUBLASTypeLess._

  // set up a Dim name USets
  var dim_names: USets[Dim] = null

  def update_dim_name(tt: TensorType): TensorType = TensorType(tt.shape.map{case Size(d, s) =>
        Size(dim_names.union_map.getOrElse(d, d), s)}, tt.et)
  def update_dim_name(anno: Anno): Anno = anno match {
    case SAnno(dim: Dim, devices, _) => SAnno(dim_names.union_map.getOrElse(dim, dim), devices)
    case a => a
  }

  override def transform(n: Node): Backend.Exp = n match {

    case Node(s, op, rs, es) if (op.startsWith("tensor_") || op.startsWith("tensors_")) =>
      val (effects, pure) = (es.deps, rs)
      val args = pure.map {
        case b @ Block(_,_,_,_) => transform(b)
        case s: Backend.Sym => transform(s)
        case Backend.Const(a: TensorType) => Backend.Const(update_dim_name(a))
        case Backend.Const(a: List[TensorType]) =>
          if (a(0).isInstanceOf[TensorType]) Backend.Const(a.map(update_dim_name)) else Backend.Const(a)
        case Backend.Const(a: Anno) => Backend.Const(update_dim_name(a))
        case a => a
      }
      val res = if (effects.nonEmpty)
        g.reflectEffect(op,args:_*)(es.rkeys.map(transform).toSeq:_*)(es.wkeys.map(transform).toSeq:_*)
      else
        g.reflect(op,args:_*)

      res

    case _ => super.transform(n)
  }

  override def transform(graph: Graph): Graph = {
    assert (g == null)
    g = new GraphBuilderOpt()
    Adapter.g = g

    try {
      val analysis = new DimNameAnalysis
      analysis.apply(graph)
      assert(dim_names == null)
      dim_names = analysis.dim_names

      super.transform(graph)
    } finally {
      g = null; Adapter.g = null
    }
  }
}
