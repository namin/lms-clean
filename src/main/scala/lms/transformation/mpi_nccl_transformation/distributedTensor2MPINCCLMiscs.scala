package lms.transformation.tensor

import scala.annotation.implicitNotFound
import scala.collection._
import scala.collection.mutable.ListBuffer

import lms.core._
import lms.core.stub._
import lms.collection.mutable._
import lms.macros.SourceContext
import lms.thirdparty.{RandomDataTypeLess, NCCLTypeLess, MPIOps, NCCLOps, SIZE_TTypeLess, CUDNNOps,CUDNNTypeLess,CLibTypeLess}
import lms.thirdparty.array_computation.{ArrayCPUTypeLess, CUDATypeLess, CUBLASTypeLess, CudaOps, CudaLibs}
import lms.transformation.util.{DataStructure, CudnnUtils}

import Backend._


trait DistributeTensor2MPI_NCCLMiscs extends DistributeTensor2MPI_NCCLBase with CudnnUtils with CudaLibs {

  import BaseTypeLess._
  import PrimitiveTypeLess._
  import RangeTypeLess._
  import ArrayTypeLess._
  import ArrayCPUTypeLess._
  import FixedSizeDistributedTensorTypeLess._
  import CUDATypeLess._
  import RandomDataTypeLess._
  import NCCLTypeLess._
  import SIZE_TTypeLess._
  import CUBLASTypeLess._
  import CUDNNTypeLess._
  import CLibTypeLess._

  override def transform(n: Node): Backend.Exp = n match {
    case Node(s, "tensor_maskedfill", Backend.Const(tt: TensorType)::Backend.Const(anno:Anno)::(input:Backend.Sym)::(mask:Backend.Sym)::
      Backend.Const(value:Float)::_, _) =>
      implicit val pos = Adapter.oldSourceMap(s)

      val shape = tensor_shape(input, useOldMetadata = true)
      val input_tensor = get_operand(input, anno)
      val mask_tensor = get_operand(mask, anno)

      val size = numeral(shape)
      val output = gpu_array(size, manifest[Float], myNCCLRank)
      cudaMaskedFillWrap[Float](
        Wrap[Array[Float]](input_tensor), 
        Wrap[Array[Float]](output.x),
        Wrap[Array[Int]](mask_tensor), 
        shape, size, value)
      output.x

    case Node(s, "tensor_maskedfill_bwd", Backend.Const(tt: TensorType)::Backend.Const(anno:Anno)::(doutput:Backend.Sym)::(mask:Backend.Sym)::_, _) =>
      implicit val pos = Adapter.oldSourceMap(s)

      val shape = tensor_shape(doutput, useOldMetadata = true)
      val doutput_tensor = get_operand(doutput, anno)
      val mask_tensor = get_operand(mask, anno)
      val size = numeral(shape)
      val dinput = gpu_array(size, manifest[Float], myNCCLRank)
      cudaMaskedFillGradWrap[Float](
        Wrap[Array[Float]](doutput_tensor), 
        Wrap[Array[Float]](dinput.x),
        Wrap[Array[Int]](mask_tensor),
        shape, size)
      dinput.x

    case Node(s, "tensor_logsoftmax", Backend.Const(tt: TensorType)::Backend.Const(anno:Anno)::(input:Backend.Sym)::_, _) =>
      implicit val pos = Adapter.oldSourceMap(s)

      val shape = tensor_shape(input, useOldMetadata = true)
      val input_tensor = get_operand(input, anno)

      val output = gpu_array(numeral(shape), manifest[Float], myNCCLRank)
      cudaLogSoftmaxWrap[Float](
        Wrap[Array[Float]](input_tensor), 
        Wrap[Array[Float]](output.x),
        numeral(shape.init),
        shape.last)
      output.x

    case Node(s, "tensor_logsoftmax_bwd", Backend.Const(tt: TensorType)::Backend.Const(anno:Anno)::(output:Backend.Sym)::(doutput:Backend.Sym)::_, _) =>
      implicit val pos = Adapter.oldSourceMap(s)

      val shape = tensor_shape(output, useOldMetadata = true)
      val output_tensor = get_operand(output, anno)
      val doutput_tensor = get_operand(doutput, anno)

      val dinput = gpu_array(numeral(shape), manifest[Float], myNCCLRank)
      cudaLogSoftmaxGradWrap[Float](
        Wrap[Array[Float]](dinput.x), 
        Wrap[Array[Float]](doutput_tensor),
        Wrap[Array[Float]](output_tensor),
        numeral(shape.init),
        shape.last)
      dinput.x

    case Node(s, "tensor_transpose", Backend.Const(tt:TensorType)::Backend.Const(anno:Anno)::(operand:Backend.Sym)::_, _) =>
      val sourceTensor = new TENSOR(s, useOldMetadata = true)

      implicit val sc_ : SourceContext = sourceTensor.pos
      val m = sourceTensor.et
      val input_tensor = get_operand(operand, anno)

      anno match {
        case NAnno => throw new Exception(s"TODO: not yet handling NAnno")
        case SAnno(dim: Dim, devices: Seq[Device], _) if tt.contains(dim) =>
          val shape = tt.shapeSizeAfterSplit(dim, devices.size)
          val output = gpu_array(numeral(shape), manifest[Float], myNCCLRank)
          cudaTransposeWrap[Float](
            Wrap[Array[Float]](input_tensor),
            Wrap[Array[Float]](output.x),
            shape)
          output.x

        case SAnno(dim: Dim, devices: Seq[Device], _) => throw new Exception(s"TODO: not yet handling SAnno with AllReduce")
        case a => throw new Exception(s"TODO: annotation $a is not yet handled")
      }

    case Node(s, "tensor_permute", Backend.Const(tt:TensorType)::Backend.Const(anno:Anno)::(operand:Backend.Sym)::Backend.Const(perm:List[Int])::_, _) =>
      val sourceTensor = new TENSOR(s, useOldMetadata = true)

      implicit val sc_ : SourceContext = sourceTensor.pos
      val m = sourceTensor.et
      val input_tensor = get_operand(operand, anno)

      anno match {
        case NAnno => throw new Exception(s"TODO: not yet handling NAnno")
        case SAnno(dim: Dim, devices: Seq[Device], _) if tt.contains(dim) =>
          val shape = tt.shapeSizeAfterSplit(dim, devices.size)
          val size = numeral(shape)
          val output = gpu_array(size, manifest[Float], myNCCLRank)
          cudaPermuteWrap[Float](
            Wrap[Array[Float]](input_tensor),
            Wrap[Array[Float]](output.x), 
            shape, size, perm)
          output.x

        case SAnno(dim: Dim, devices: Seq[Device], _) => throw new Exception(s"TODO: not yet handling SAnno with AllReduce")
        case a => throw new Exception(s"TODO: annotation $a is not yet handled")
      }

    case Node(s, "tensor_embedding", Backend.Const(tt:TensorType)::Backend.Const(anno:Anno)::(input:Backend.Sym)::(indices:Backend.Sym)::_, _) =>
      val sourceTensor = new TENSOR(s, useOldMetadata = true)

      implicit val sc_ : SourceContext = sourceTensor.pos
      val m = sourceTensor.et
      val input_tensor = get_operand(input, anno)
      val indices_array = get_operand(indices, anno)

      val inputTensorType = (new TENSOR(input, useOldMetadata = true)).resultType
      val indicesTensorType = (new TENSOR(indices, useOldMetadata = true)).resultType
      val outputTensorType = (new TENSOR(s, useOldMetadata = true)).resultType

      val input_shape = tensor_shape(input, useOldMetadata = true)
      val indices_shape = tensor_shape(indices, useOldMetadata = true)
      val output_shape = tensor_shape(s, useOldMetadata = true)

      anno match {
        case NAnno => throw new Exception(s"TODO: not yet handling NAnno")
        case SAnno(dim: Dim, devices: Seq[Device], _) if tt.contains(dim) =>
          val indices_size = indicesTensorType.shapeSizeAfterSplit(dim, devices.size)(0)
          val embed_size = inputTensorType.shapeSizeAfterSplit(dim, devices.size)(1)
          val output_size = numeral(outputTensorType.shapeSizeAfterSplit(dim, devices.size))
          val output = gpu_array(output_size, manifest[Float], myNCCLRank)
          cudaEmbeddingWrap[Float](
            Wrap[Array[Float]](input_tensor),
            Wrap[Array[Float]](output.x),
            Wrap[Array[Int]](indices_array),
            unit[Int](embed_size),
            unit[Int](indices_size))
          /*
          NCCL_ALLREDUCE(m, output, output, SIZE_T(output_size), NCCL_SUM, myNCCLComm, myNCCLStream)
          CUDA_STREAM_SYNCHRONIZE(myNCCLStream)*/
          output.x

        case SAnno(dim: Dim, devices: Seq[Device], _) => throw new Exception(s"TODO: not yet handling SAnno with AllReduce")
        case a => throw new Exception(s"TODO: annotation $a is not yet handled")
      }

    case Node(s, "tensor_embedding_bwd", Backend.Const(tt:TensorType)::Backend.Const(anno:Anno)::(doutput:Backend.Sym)::(indices:Backend.Sym)::_, _) =>
      val sourceTensor = new TENSOR(s, useOldMetadata = true)

      implicit val sc_ : SourceContext = sourceTensor.pos
      val m = sourceTensor.et
      val doutput_tensor = get_operand(doutput, anno)
      val indices_array = get_operand(indices, anno)

      val doutputTensorType = (new TENSOR(doutput, useOldMetadata = true)).resultType
      val indicesTensorType = (new TENSOR(indices, useOldMetadata = true)).resultType
      val dinputTensorType = (new TENSOR(s, useOldMetadata = true)).resultType

      val doutput_shape = tensor_shape(doutput, useOldMetadata = true)
      val indices_shape = tensor_shape(indices, useOldMetadata = true)
      val dinput_shape = tensor_shape(s, useOldMetadata = true)

      anno match {
        case NAnno => throw new Exception(s"TODO: not yet handling NAnno")
        case SAnno(dim: Dim, devices: Seq[Device], _) if tt.contains(dim) =>
          val indices_size = indicesTensorType.shapeSizeAfterSplit(dim, devices.size)(0)
          val embed_size = doutputTensorType.shapeSizeAfterSplit(dim, devices.size)(1)
          val dinput_size = numeral(dinputTensorType.shapeSizeAfterSplit(dim, devices.size))
          val dinput = gpu_array(dinput_size, manifest[Float], myNCCLRank)
          cudaEmbeddingGradWrap[Float](
            Wrap[Array[Float]](doutput_tensor),
            Wrap[Array[Float]](dinput.x),
            Wrap[Array[Int]](indices_array),
            unit[Int](embed_size),
            unit[Int](indices_size))
          /*
          NCCL_ALLREDUCE(m, dinput, dinput, SIZE_T(dinput_size), NCCL_SUM, myNCCLComm, myNCCLStream)
          CUDA_STREAM_SYNCHRONIZE(myNCCLStream)*/
          dinput.x

        case SAnno(dim: Dim, devices: Seq[Device], _) => throw new Exception(s"TODO: not yet handling SAnno with AllReduce")
        case a => throw new Exception(s"TODO: annotation $a is not yet handled")
      }

    case _ => super.transform(n)
  }
}
