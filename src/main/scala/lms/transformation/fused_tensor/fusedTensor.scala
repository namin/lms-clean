package lms.transformation.tensor

import scala.annotation.implicitNotFound
import scala.collection._

import lms.core._
import lms.core.stub._
import lms.collection.mutable._
import lms.macros.SourceContext
import lms.thirdparty.array_computation.{ArrayCPUOps, CUDATypeLess, CudaOps}

import Backend._

object FusedTensorTypeLess {
  import BaseTypeLess._
  import PrimitiveTypeLess._
  import ArrayTypeLess._
  import CUDATypeLess._

  type E = Backend.Exp
  def C(a: Any) = Backend.Const(a)

  /// typeless frontend
  def TENSOR(size: Int)(f: Backend.Exp => Backend.Exp)(implicit __pos: SourceContext): TENSOR = {
    (new TENSOR(Adapter.g.reflectUnsafe("tensor", C(size), Adapter.g.reify(xn => f(xn))))).withSrcType(__pos, manifest[Int])
  }

  def ZEROS(size: Int)(implicit __pos: SourceContext): TENSOR = {
    (new TENSOR(Adapter.g.reflectUnsafe("tensor_zeros", C(size)))).withSrcType(__pos, manifest[Int])
  }

  def ONES(size: Int)(implicit __pos: SourceContext): TENSOR = {
    (new TENSOR(Adapter.g.reflectUnsafe("tensor_ones", C(size)))).withSrcType(__pos, manifest[Int])
  }

  def CONSTS(size: Int, num: Int)(implicit __pos: SourceContext): TENSOR = {
    (new TENSOR(Adapter.g.reflectUnsafe("tensor_consts", C(size), C(num)))).withSrcType(__pos, manifest[Int])
  }

  class TENSOR(override val x: Backend.Exp, override val useOldMetadata: Boolean = false) extends TOP(x) {
    def withEleType(m: Manifest[_]): this.type = { Adapter.typeMap(x) = m; this }
    override def withSrcType(pos: SourceContext, m: Manifest[_]): this.type =
      withSource(pos).withEleType(m)

    def et: Manifest[_] = {
      if (useOldMetadata) Adapter.oldTypeMap(x) else Adapter.typeMap(x)
    }

    def size: Int = {
      gc.get(x.asInstanceOf[Backend.Sym]) match {
        case Some(Node(_, s, Backend.Const(size:Int)::_, _)) => size
        case a => System.out.println(a); ???
      }
    }
    
    def arr: Backend.Sym = {
      gc.get(x.asInstanceOf[Backend.Sym]) match {
        case Some(Node(_, s, Backend.Const(size:Int)::(arr:Backend.Sym)::_, _)) => arr
        case a => System.out.println(a); ???
      }
    }

    def show(implicit __pos: SourceContext): UNIT = {
      UNIT(Adapter.g.reflectEffect("show_tensor", x)()(Adapter.CTRL))
    }

    def + (y: TENSOR)(implicit __pos: SourceContext): TENSOR = {
      (new TENSOR(Adapter.g.reflectUnsafe("tensor_add", x, y.x))).withSrcType(__pos, et)
    }

    def - (y: TENSOR)(implicit __pos: SourceContext): TENSOR = {
      (new TENSOR(Adapter.g.reflect("tensor_minus", x, y.x))).withSrcType(__pos, et)
    }

    def tanh(implicit __pos: SourceContext): TENSOR = {
      (new TENSOR(Adapter.g.reflect("tensor_tanh", x))).withSrcType(__pos, et)
    }

    def relu(implicit __pos: SourceContext): TENSOR = {
      (new TENSOR(Adapter.g.reflect("tensor_relu", x))).withSrcType(__pos, et)
    }

    def apply(e: Backend.Exp)(implicit __pos: SourceContext): INT = {
      // INT(Adapter.g.reflectEffect("tensor_apply", x, e)()(Adapter.CTRL)).withSrcType(__pos, et)
      INT(Adapter.g.reflect("tensor_apply", x, e)).withSrcType(__pos, et)
    }
  }
}


trait FusedTensorOps extends Dsl with ArrayOps with CudaOps {

  import PrimitiveTypeLess._
  import ArrayTypeLess._
  import FusedTensorTypeLess._

  // def NewArray[T:Manifest](x: Rep[Int])(implicit __pos: SourceContext): Rep[Array[T]] = {
  //   Wrap[Array[T]](ARRAY(new INT(Unwrap(x)), manifest[T]).x)
  // }

  /// Typed Frontend
  class Tensor[+T]
  object Tensor {
    def zeros[T:Manifest](size: Int)(implicit __pos: SourceContext): Rep[Tensor[T]] = {
      val tensor = ZEROS(size)
      Wrap[Tensor[T]](tensor.x)
    }

    def ones[T:Manifest](size: Int)(implicit __pos: SourceContext): Rep[Tensor[T]] = {
      val tensor = ONES(size)
      Wrap[Tensor[T]](tensor.x)
    }

    def consts[T:Manifest](size: Int, num: Int)(implicit __pos: SourceContext): Rep[Tensor[T]] = {
      val tensor = CONSTS(size, num)
      Wrap[Tensor[T]](tensor.x)
    }

    def apply[T:Numeric:Manifest](size: Int, f: Rep[Int] => Rep[Int])(implicit __pos: SourceContext): Rep[Tensor[T]] = {
      Wrap[Tensor[T]](TENSOR(size)(unwrapFun[Int, Int](f)).x)
    }
  }

  def tensor[T:Numeric:Manifest](x: Rep[Tensor[T]]): TENSOR = new TENSOR(Unwrap(x))

  implicit class TensorOps[T:Numeric:Manifest](x: Rep[Tensor[T]]) {
    val self = tensor(x)

    def show(implicit __pos: SourceContext): Rep[Unit] = Wrap[Unit](self.show.x)

    def + (y: Rep[Tensor[T]])(implicit  __pos: SourceContext): Rep[Tensor[T]] = {
      val t = self + tensor(y)
      Wrap[Tensor[T]](t.x)
    }

    def - (y: Rep[Tensor[T]])(implicit  __pos: SourceContext): Rep[Tensor[T]] = {
      val t = self - tensor(y)
      Wrap[Tensor[T]](t.x)
    }

    def tanh(implicit  __pos: SourceContext): Rep[Tensor[T]] = {
      val t = self.tanh
      Wrap[Tensor[T]](t.x)
    }

    def relu(implicit  __pos: SourceContext): Rep[Tensor[T]] = {
      val t = self.relu
      Wrap[Tensor[T]](t.x)
    }

    def apply(y: Rep[Int])(implicit  __pos: SourceContext): Rep[T] = {
      val t = self.apply(Unwrap(y))
      Wrap[T](t.x)
    }

  }
}
