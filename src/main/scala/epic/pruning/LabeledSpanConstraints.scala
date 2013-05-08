package epic.pruning

import scala.collection.BitSet
import breeze.collection.mutable.TriangularArray
import epic.pruning.LabeledSpanConstraints._
import java.util
import scala.collection.mutable.ArrayBuffer
import scala.annotation.unchecked.uncheckedVariance

/**
 * Represents
 *
 * @author dlwh
 */
sealed trait LabeledSpanConstraints[-L] extends SpanConstraints {
  def isAllowedLabeledSpan(begin: Int, end: Int, label: Int): Boolean
  def isAllowedSpan(begin: Int, end: Int): Boolean
  def maxSpanLengthStartingAt(begin: Int):Int
  def maxSpanLengthForLabel(label: Int):Int

  // TODO... what's the right thing here?
  def &(other: LabeledSpanConstraints[L @uncheckedVariance ]): LabeledSpanConstraints[L] = this match {
    case NoConstraints => this
    case SimpleConstraints(maxPosX, maxLx, x) => other match {
      case NoConstraints => other
      case SimpleConstraints(maxPosY, maxLy, y) =>
        require(x.dimension == y.dimension, "Dimensions of constrained spans must match!")
        SimpleConstraints( elementwiseMin(maxPosX, maxPosY), elementwiseMin(maxLx, maxLy),
          TriangularArray.tabulate(x.dimension) { (b,e) =>
          if(x(b,e) == null || y(b,e) == null) null
          else  x(b,e) & y(b,e)
        })
    }
  }

  def |(other: LabeledSpanConstraints[L @uncheckedVariance ]): LabeledSpanConstraints[L] = this match {
    case NoConstraints => other
    case SimpleConstraints(maxPosX, maxLx, x) => other match {
      case NoConstraints => this
      case SimpleConstraints(maxPosY, maxLy, y) =>
        require(x.dimension == y.dimension, "Dimensions of constrained spans must match!")
        SimpleConstraints( elementwiseMax(maxPosX, maxPosY), elementwiseMax(maxLx, maxLy),
          TriangularArray.tabulate(x.dimension) { (b,e) =>
            if(x(b,e) == null) y(b,e)
            else if (y(b,e) == null) x(b, e)
            else  x(b,e) | y(b,e)
          x(b,e) | y(b,e)
        })
    }
  }


}

object LabeledSpanConstraints {

  def noConstraints[L]:LabeledSpanConstraints[L] = NoConstraints

  def apply[L](spans: TriangularArray[_ <: BitSet]):LabeledSpanConstraints[L] = {
    val maxLengthPos = Array.tabulate(spans.dimension-1) { begin =>
      val maxEnd = ((spans.dimension-1) until begin by -1).find(end => spans(begin,end) != null && spans(begin,end).nonEmpty).getOrElse(begin)
      maxEnd - begin
    }
    val maxLengthLabel = ArrayBuffer[Int]()
    for(begin <- 0 until spans.dimension; end <- (begin+1) until spans.dimension) {
      if(spans(begin, end) ne null) {
        for(l <- spans(begin, end)) {
          if(l >= maxLengthLabel.length) {
            maxLengthLabel ++= new Array[Int](l - maxLengthLabel.length + 1)
          }
          maxLengthLabel(l) = maxLengthLabel(l) max (end-begin)
        }

      }
    }

    apply(maxLengthPos, maxLengthLabel.toArray, spans)
  }

  def apply[L](maxLengthPos: Array[Int], maxLengthLabel: Array[Int], spans: TriangularArray[_ <: BitSet]):LabeledSpanConstraints[L] = {
    SimpleConstraints(maxLengthPos, maxLengthLabel, spans)
  }

  def fromTagConstraints[L](constraints: TagConstraints[L]): LabeledSpanConstraints[L] = {
    val arr = TriangularArray.tabulate(constraints.length+1) { (b,e) =>
      if(b +1 == e) {
        ensureBitSet(constraints.allowedTags(b))
      } else {
        null
      }
    }
    apply(arr)
  }


  private def ensureBitSet[L](tags: Set[Int]): BitSet = {
    tags match {
      case x: BitSet => x
      case x => BitSet.empty ++ x
    }
  }

  def layeredFromTagConstraints[L](localization: TagConstraints[L], maxLengthForLabel: Array[Int]): LabeledSpanConstraints[L] = {
    val arr = new TriangularArray[BitSet](localization.length + 1)
    val maxMaxLength = maxLengthForLabel.max
    println(maxMaxLength)
    for(i <- 0 until localization.length) {
       arr(i, i+1) = ensureBitSet(localization.allowedTags(i))
    }

    val maxLengthPos = Array.fill(localization.length)(1)
    val maxLengthLabel = Array.fill(localization.length)(1)

    var acceptableTags = BitSet.empty ++ (0 until maxLengthForLabel.length)
    for(length <- 2 to maxMaxLength if acceptableTags.nonEmpty) {
      acceptableTags = acceptableTags.filter(i => maxLengthForLabel(i) >= length)
      if(acceptableTags.nonEmpty)
        for (begin <- 0 until (localization.length - length) ) {
          val end = begin + length
          arr(begin, end) = (arr(begin, begin+1) & arr(begin+1, end)) & acceptableTags
          if(arr(begin,end).isEmpty) {
            arr(begin, end) = null
          } else {
            maxLengthPos(begin) = length
            for(t <- arr(begin, end))
              maxLengthLabel(t) = length
          }
        }
    }
    apply(maxLengthPos, maxLengthLabel, arr)
  }

  def maxLengthConstraints(length: Int, maxLengthForLabel: Array[Int]):SpanConstraints = {
    val arr = new TriangularArray[BitSet](length + 1)
    val maxMaxLength = maxLengthForLabel.max
    val maxLengthPos = Array.fill(length)(1)

    var acceptableTags = BitSet.empty ++ (0 until maxLengthForLabel.length)
    for(len <- 1 to maxMaxLength if acceptableTags.nonEmpty) {
      acceptableTags = acceptableTags.filter(i => maxLengthForLabel(i) >= length)
      if(acceptableTags.nonEmpty)
        for (begin <- 0 until (length - len)) {
          val end = begin + len
          arr(begin, end) = acceptableTags
          println(acceptableTags)
          maxLengthPos(begin) = length
        }
    }

    println(maxMaxLength + " " + maxLengthPos.mkString(":"))
    apply(maxLengthPos, maxLengthForLabel, arr)
  }


  @SerialVersionUID(1L)
  object NoConstraints extends LabeledSpanConstraints[Any] with Serializable {

    def maxSpanLengthStartingAt(begin: Int): Int = Int.MaxValue/2 // /2 because i get worried about wrap around.

    def isAllowedSpan(begin: Int, end: Int): Boolean = true
    def isAllowedLabeledSpan(begin: Int, end: Int, label: Int): Boolean = true

    def maxSpanLengthForLabel(label: Int):Int = Int.MaxValue / 2
  }

  @SerialVersionUID(1L)
  case class SimpleConstraints[L](private val maxLengthsForPosition: Array[Int],  // maximum length for position
                                  private val maxLengthsForLabel: Array[Int],
                                  private val spans: TriangularArray[_ <:BitSet]) extends LabeledSpanConstraints[L] with Serializable {
    def isAllowedSpan(begin: Int, end: Int): Boolean = (spans(begin,end) ne null) && spans(begin,end).nonEmpty

    def isAllowedLabeledSpan(begin: Int, end: Int, label: Int): Boolean = (spans(begin,end) ne null) && spans(begin, end).contains(label)

    def maxSpanLengthStartingAt(begin: Int): Int = maxLengthsForPosition(begin)

    def maxSpanLengthForLabel(label: Int) = if(maxLengthsForLabel.length <= label) 0 else maxLengthsForLabel(label)
  }



  private def elementwiseMax(a: Array[Int], b: Array[Int]):Array[Int] = {
    // could avoid the allocation, but whatever.
    if(a.length < b.length) elementwiseMax(util.Arrays.copyOf(a, b.length), b)
    else if(b.length < a.length) elementwiseMax(a, util.Arrays.copyOf(b, a.length))
    else {
      val result = new Array[Int](a.length)
      var i = 0
      while(i < result.length) {
        result(i) = math.max(a(i), b(i))
        i += 1
      }
      result
    }
  }

  private def elementwiseMin(a: Array[Int], b: Array[Int]):Array[Int] = {
    // could avoid the allocation, but whatever.
    if(a.length < b.length) elementwiseMin(util.Arrays.copyOf(a, b.length), b)
    else if(b.length < a.length) elementwiseMin(a, util.Arrays.copyOf(b, a.length))
    else {
      val result = new Array[Int](a.length)
      var i = 0
      while(i < result.length) {
        result(i) = math.min(a(i), b(i))
        i += 1
      }
      result
    }
  }

}
