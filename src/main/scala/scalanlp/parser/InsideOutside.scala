package scalanlp.parser

import scalanlp.collection.mutable.SparseArray
import scalala.tensor.counters.LogCounters.LogDoubleCounter
import scalala.tensor.counters.LogCounters.LogPairedDoubleCounter
import scalala.tensor.Vector
import scalala.tensor.counters.LogCounters;
import scalanlp.math.Numerics.logSum;

import ParseChart._;
import ChartParser._;
import InsideOutside._;

/**
 * 
 * @author dlwh
 */
class InsideOutside[L,W](parser: ChartParser[LogProbabilityParseChart,L,W]) {
  def this(root: L, g: Grammar[L], lexicon: Lexicon[L,W])  = {
    this(new CKYParser[ParseChart.LogProbabilityParseChart,L,W](root,lexicon,g,logProb));
  }

  def grammar = parser.grammar;
  def lexicon = parser.lexicon;
  def root = parser.root;

  def expectedCounts(words: Seq[W], validSpan:ChartParser.SpanFilter =ChartParser.defaultFilterBoxed):ExpectedCounts[W] = {
    val inside = parser.buildInsideChart(words, validSpan);
    val outside = parser.buildOutsideChart(inside, validSpan);
    val totalProb = inside.labelScore(0, words.length, root);


    val wordCounts = computeWordCounts(words, inside, outside, totalProb)
    val binaryRuleCounts = computeBinaryCounts(words, inside, outside, validSpan, totalProb)
    val unaryRuleCounts = computeUnaryCounts(words, inside, outside, validSpan, totalProb)

    ExpectedCounts(binaryRuleCounts, unaryRuleCounts, wordCounts, totalProb);
  }

  private def computeWordCounts(words: scala.Seq[W],
                        inside: LogProbabilityParseChart[L],
                        outside: LogProbabilityParseChart[L],
                        totalProb: Double): SparseArray[LogDoubleCounter[W]] = {
    val wordCounts = grammar.fillSparseArray(LogDoubleCounter[W]());
    // handle lexical productions:
    for (i <- 0 until words.length) {
      val w = words(i);
      for (l <- inside.enteredLabelIndexes(i, i + 1) if isTag(l)) {
        val iScore = inside.labelScore(i, i + 1, l);
        val oScore = outside.labelScore(i, i + 1, l);
        wordCounts(l)(w) = logSum( wordCounts(l)(w),(iScore + oScore) - totalProb);
      }
    }
    wordCounts
  }

  private def computeBinaryCounts(words: scala.Seq[W],
                                  inside: LogProbabilityParseChart[L],
                                  outside: LogProbabilityParseChart[L],
                                  validSpan: SpanFilter, totalProb: Double) = {
    val binaryRuleCounts = grammar.fillSparseArray(grammar.fillSparseArray(grammar.mkVector(Double.NegativeInfinity)));
    // handle binary rules
    for{
      span <- 2 to words.length;
      begin <- 0 to (words.length - span);
      end = begin + span
      (b, binaryRules) <- grammar.allBinaryRules;
      if inside.canStartHere(begin, end, b);
      (c, parentVector) <- binaryRules;
      split <- inside.feasibleSpan(begin, end, b, c)
    } {
      val bScore = inside.labelScore(begin, split, b);
      if (!bScore.isInfinite) {
        val cScore = inside.labelScore(split, end, c)
        if (!cScore.isInfinite) {
          var i = 0;
          while (i < parentVector.used) {
            val a = parentVector.index(i);
            val rScore = parentVector.data(i);
            val aScore = outside.labelScore(begin, end, a);
            i += 1;
            if ((validSpan eq defaultFilterBoxed) || validSpan(begin, end, a)) {
              val prob = bScore + cScore + aScore + rScore - totalProb;
              binaryRuleCounts(a)(b)(c) = logSum(binaryRuleCounts(a)(b)(c), prob);
            }
          }
        }
      }
    }
    binaryRuleCounts
  }

  private def computeUnaryCounts(words: scala.Seq[W],
                                 inside: LogProbabilityParseChart[L],
                                 outside: LogProbabilityParseChart[L],
                                 validSpan: SpanFilter,
                                 totalProb: Double): SparseArray[Vector] = {
    val unaryRuleCounts = grammar.fillSparseArray(grammar.mkVector(Double.NegativeInfinity));
    for{
      span <- 1 to words.length;
      begin <- 0 to (words.length - span);
      end = begin + span
      b <- inside.enteredLabelIndexes(begin, end)
    } {
      val bScore = inside.labelScore(begin, end, b);
      if (!bScore.isInfinite) {
        val parentVector = grammar.unaryRulesByIndexedChild(b);
        var i = 0;
        while (i < parentVector.used) {
          val a = parentVector.index(i);
          val rScore = parentVector.data(i);
          val aScore = outside.labelScore(begin, end, a);
          i += 1;
          if ((validSpan eq defaultFilterBoxed) || validSpan(begin, end, a)) {
            val prob = bScore + aScore + rScore - totalProb;
            unaryRuleCounts(a)(b) = logSum(unaryRuleCounts(a)(b), prob);
          }
        }
      }
    }
    unaryRuleCounts
  }


  private val isTag = new collection.mutable.BitSet();
  lexicon.tags.foreach {l => isTag += grammar.index(l)};
}

object InsideOutside {

  final case class ExpectedCounts[W](
    binaryRuleCounts: SparseArray[SparseArray[Vector]], // parent -> lchild -> rchild -> counts;
    unaryRuleCounts: SparseArray[Vector], // parent -> child -> counts;
    wordCounts: SparseArray[LogDoubleCounter[W]], // parent -> word -> counts;
    var logProb: Double
  ) {

    def this(g: Grammar[_]) = this(g.fillSparseArray(g.fillSparseArray(g.mkVector(Double.NegativeInfinity))),
                                   g.fillSparseArray(g.mkVector(Double.NegativeInfinity)),
                                   g.fillSparseArray(LogDoubleCounter[W]()), 0.0);

    def +=(c: ExpectedCounts[W]) = {
      val ExpectedCounts(bCounts,uCounts,wCounts,tProb) = c;

      for( (k1,c) <- bCounts;
          (k2,vec) <- c) {
        logAdd(binaryRuleCounts(k1)(k2),vec);
      }

      for( (k,vec) <- uCounts) {
        logAdd(unaryRuleCounts(k),vec);
      }

      for( (k,vec) <- wCounts) {
        logAdd(wordCounts(k),vec);
      }

      logProb += tProb;
      this;
    }

    def decode[L](g: Grammar[L]) = (decodeRules(g,binaryRuleCounts,unaryRuleCounts),decodeWords(g,wordCounts));
  }

    def decodeRules[L](g: Grammar[L],
                     binaryRuleCounts: SparseArray[SparseArray[Vector]],
                     unaryRuleCounts: SparseArray[Vector]) = {
    val ctr = LogPairedDoubleCounter[L,Rule[L]]();

    for( (pIndex,arr1) <- binaryRuleCounts.iterator;
        p = g.index.get(pIndex);
        (lIndex,arr2) <- arr1.iterator;
        l = g.index.get(lIndex);
        (rIndex,v) <- arr2.activeElements;
        r = g.index.get(rIndex)
    ) {
      ctr(p,BinaryRule(p,l,r)) = v;
    }

    for( (pIndex,arr1) <- unaryRuleCounts.iterator;
        p = g.index.get(pIndex);
        (cIndex,v) <- arr1.activeElements;
        c = g.index.get(cIndex)
    ) {
      ctr(p,UnaryRule(p,c)) = v;
    }

    ctr;
  }

  def decodeWords[L,W](g: Grammar[L], wordCounts: SparseArray[LogDoubleCounter[W]]) = {
    val ctr = LogPairedDoubleCounter[L,W]();
    for( (i,c) <- wordCounts) {
      ctr(g.index.get(i)) := c;
    }
    ctr;
  }


  private def logAdd(v: Vector, v2: Vector) {
    for( (i,w) <- v2.activeElements) {
      v(i) = logSum(v(i),w);
    }
  }

  private def logAdd[W](v: LogDoubleCounter[W], v2: LogDoubleCounter[W]) {
    for( (i,w) <- v2.iterator) {
      v(i) = logSum(v(i),w);
    }
  }
}