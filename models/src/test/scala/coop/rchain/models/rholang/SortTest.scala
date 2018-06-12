package coop.rchain.models.rholang

import coop.rchain.models.Channel.ChannelInstance._
import coop.rchain.models.Connective.ConnectiveInstance._
import coop.rchain.models.Expr.ExprInstance._
import coop.rchain.models.Var.VarInstance.{BoundVar, FreeVar, Wildcard}
import coop.rchain.models._
import coop.rchain.models.rholang.implicits._
import coop.rchain.models.rholang.sort._
import org.scalatest._
import coop.rchain.models.rholang.sort.ordering._
import scala.collection.immutable.BitSet

class ScoredTermSpec extends FlatSpec with Matchers {
  "ScoredTerm" should "Sort so that shorter nodes come first" in {
    val unsortedTerms =
      Seq(ScoredTerm("foo", Leaves(1, 2, 2, 3)), ScoredTerm("bar", Leaves(1, 2, 2)))
    val sortedTerms = Seq(ScoredTerm("bar", Leaves(1, 2, 2)), ScoredTerm("foo", Leaves(1, 2, 2, 3)))
    unsortedTerms.sorted should be(sortedTerms)
  }
  "ScoredTerm" should "Sort so that smaller leafs stay first" in {
    val unsortedTerms = Seq(ScoredTerm("foo", Leaf(1)), ScoredTerm("bar", Leaf(2)))
    val sortedTerms   = Seq(ScoredTerm("foo", Leaf(1)), ScoredTerm("bar", Leaf(2)))
    unsortedTerms.sorted should be(sortedTerms)
  }
  "ScoredTerm" should "Sort so that smaller leafs are put first" in {
    val unsortedTerms = Seq(ScoredTerm("foo", Leaf(2)), ScoredTerm("bar", Leaf(1)))
    val sortedTerms   = Seq(ScoredTerm("bar", Leaf(1)), ScoredTerm("foo", Leaf(2)))
    unsortedTerms.sorted should be(sortedTerms)
  }
  "ScoredTerm" should "Sort so that smaller nodes are put first" in {
    val unsortedTerms = Seq(ScoredTerm("foo", Node(Seq(Leaves(1, 2), Leaves(2, 2)))),
                            ScoredTerm("bar", Node(Seq(Leaves(1, 1), Leaves(2, 2)))))
    val sortedTerms = Seq(ScoredTerm("bar", Node(Seq(Leaves(1, 1), Leaves(2, 2)))),
                          ScoredTerm("foo", Node(Seq(Leaves(1, 2), Leaves(2, 2)))))
    unsortedTerms.sorted should be(sortedTerms)
  }
}

class VarSortMatcherSpec extends FlatSpec with Matchers {
  val p = Par()
  "Different kinds of variables" should "bin separately" in {
    val parVars = p.copy(
      exprs = List(EVar(BoundVar(2)),
                   EVar(Wildcard(Var.WildcardMsg())),
                   EVar(BoundVar(1)),
                   EVar(FreeVar(0)),
                   EVar(FreeVar(2)),
                   EVar(BoundVar(0)),
                   EVar(FreeVar(1))),
      locallyFree = BitSet(0, 1, 2),
      connectiveUsed = true
    )
    val sortedParVars: Option[Par] = p.copy(
      exprs = List(EVar(BoundVar(0)),
                   EVar(BoundVar(1)),
                   EVar(BoundVar(2)),
                   EVar(FreeVar(0)),
                   EVar(FreeVar(1)),
                   EVar(FreeVar(2)),
                   EVar(Wildcard(Var.WildcardMsg()))),
      locallyFree = BitSet(0, 1, 2),
      connectiveUsed = true
    )
    val result = ParSortMatcher.sortMatch(parVars)
    result.term should be(sortedParVars.get)
  }
}

class ParSortMatcherSpec extends FlatSpec with Matchers {
  val p = Par()
  "Par" should "Sort so that smaller integers come first" in {
    val parGround =
      p.copy(exprs = List(GInt(2), GInt(1), GInt(-1), GInt(-2), GInt(0)))
    val sortedParGround: Option[Par] =
      p.copy(exprs = List(GInt(-2), GInt(-1), GInt(0), GInt(1), GInt(2)))
    val result = ParSortMatcher.sortMatch(parGround)
    result.term should be(sortedParGround.get)
  }

  "Par" should "Sort in order of boolean, int, string, uri" in {
    val parGround =
      p.copy(
        exprs = List(GUri("https://www.rchain.coop/"), GInt(47), GString("Hello"), GBool(true)))
    val sortedParGround: Option[Par] =
      p.copy(
        exprs = List(GBool(true), GInt(47), GString("Hello"), GUri("https://www.rchain.coop/")))
    val result = ParSortMatcher.sortMatch(parGround)
    result.term should be(sortedParGround.get)
  }

  "Par" should "Sort and deduplicate sets insides" in {
    val parGround =
      ParSet(
        Seq[Par](
          GInt(2),
          GInt(1),
          ParSet(Seq[Par](GInt(1), GInt(2))),
          ParSet(Seq[Par](GInt(1), GInt(1)))
        )
      )
    val sortedParGround: Option[Par] =
      ParSet(
        Seq[Par](
          GInt(1),
          GInt(2),
          ParSet(Seq[Par](GInt(1))),
          ParSet(Seq[Par](GInt(1), GInt(2)))
        )
      )
    val result = ParSortMatcher.sortMatch(parGround)
    result.term should be(sortedParGround.get)
  }

  "Par" should "Sort map insides by key and last write should win" in {
    val parGround =
      EMap(
        List(
          KeyValuePair(GInt(2), ParSet(Seq[Par](GInt(2), GInt(1)))),
          KeyValuePair(GInt(2), GInt(1)),
          KeyValuePair(GInt(1), GInt(1))
        ),
        locallyFree = BitSet()
      )
    val sortedParGround: Option[Par] =
      EMap(List(KeyValuePair(GInt(1), GInt(1)), KeyValuePair(GInt(2), GInt(1))),
           locallyFree = BitSet())
    val result = ParSortMatcher.sortMatch(parGround)
    result.term should be(sortedParGround.get)
  }

  "Par" should "Keep order when adding numbers" in {
    val parExpr: Par =
      EPlus(EPlus(GInt(1), GInt(3)), GInt(2))
    val result = ParSortMatcher.sortMatch(parExpr)
    result.term should be(parExpr.get)
  }

  "Par" should "Sort according to PEMDAS" in {
    val parExpr =
      p.copy(
        exprs = List(EMinus(GInt(4), GInt(3)),
                     EDiv(GInt(1), GInt(5)),
                     EPlus(GInt(1), GInt(3)),
                     EMult(GInt(6), GInt(3))))
    val sortedParExpr: Par =
      p.copy(
        exprs = List(
          EMult(GInt(6), GInt(3)),
          EDiv(GInt(1), GInt(5)),
          EPlus(GInt(1), GInt(3)),
          EMinus(GInt(4), GInt(3))
        ))
    val result = ParSortMatcher.sortMatch(parExpr)
    result.term should be(sortedParExpr.get)
  }

  "Par" should "Sort comparisons in order of LT, LTE, GT, GTE, EQ, NEQ" in {
    val parExpr =
      p.copy(
        exprs = List(
          EEq(GInt(4), GInt(3)),
          ENeq(GInt(1), GInt(5)),
          ELt(GInt(1), GInt(5)),
          EGt(GBool(false), GBool(true)),
          ELte(GInt(1), GInt(5)),
          EGte(GBool(false), GBool(true))
        ))
    val sortedParExpr: Option[Par] =
      p.copy(
        exprs = List(
          ELt(GInt(1), GInt(5)),
          ELte(GInt(1), GInt(5)),
          EGt(GBool(false), GBool(true)),
          EGte(GBool(false), GBool(true)),
          EEq(GInt(4), GInt(3)),
          ENeq(GInt(1), GInt(5))
        ))
    val result = ParSortMatcher.sortMatch(parExpr)
    result.term should be(sortedParExpr.get)
  }

  "Par" should "sort methods after other expressions" in {
    val parExpr =
      p.copy(
        exprs = List(
          EOr(EVar(BoundVar(0)), EVar(BoundVar(1))),
          EMethod("nth", EVar(BoundVar(2)), List(GInt(1)), locallyFree = BitSet(2)),
          EOr(EVar(BoundVar(3)), EVar(BoundVar(4)))
        ))
    val sortedParExpr: Option[Par] =
      p.copy(
        exprs = List(
          EOr(EVar(BoundVar(0)), EVar(BoundVar(1))),
          EOr(EVar(BoundVar(3)), EVar(BoundVar(4))),
          EMethod("nth", EVar(BoundVar(2)), List(GInt(1)), locallyFree = BitSet(2))
        ))
    val result = ParSortMatcher.sortMatch(parExpr)
    result.term should be(sortedParExpr.get)
  }

  "Par" should "sort methods based on methodName, target, and arguments" in {
    val parExpr =
      p.copy(
        exprs = List(
          EMethod("nth", EVar(BoundVar(2)), List(GInt(1)), locallyFree = BitSet(2)),
          EMethod("mth", EVar(BoundVar(2)), List(GInt(1)), locallyFree = BitSet(2)),
          EMethod("nth", EVar(BoundVar(2)), List(GInt(2), GInt(3)), locallyFree = BitSet(2)),
          EMethod("nth", EVar(BoundVar(2)), List(GInt(2)), locallyFree = BitSet(2))
        ))
    val sortedParExpr: Option[Par] =
      p.copy(
        exprs = List(
          EMethod("mth", EVar(BoundVar(2)), List(GInt(1)), locallyFree = BitSet(2)),
          EMethod("nth", EVar(BoundVar(2)), List(GInt(1)), locallyFree = BitSet(2)),
          EMethod("nth", EVar(BoundVar(2)), List(GInt(2)), locallyFree = BitSet(2)),
          EMethod("nth", EVar(BoundVar(2)), List(GInt(2), GInt(3)), locallyFree = BitSet(2)),
        ))
    val result = ParSortMatcher.sortMatch(parExpr)
    result.term should be(sortedParExpr.get)
  }

  "Par" should "Sort Sends based on their persistence, channel, data" in {
    val parExpr =
      p.copy(
        sends = List(
          Send(Quote(GInt(5)), List(GInt(3)), false, BitSet()),
          Send(Quote(GInt(5)), List(GInt(3)), true, BitSet()),
          Send(Quote(GInt(4)), List(GInt(2)), false, BitSet()),
          Send(Quote(GInt(5)), List(GInt(2)), false, BitSet())
        ))
    val sortedParExpr: Option[Par] =
      p.copy(
        sends = List(
          Send(Quote(GInt(4)), List(GInt(2)), false, BitSet()),
          Send(Quote(GInt(5)), List(GInt(2)), false, BitSet()),
          Send(Quote(GInt(5)), List(GInt(3)), false, BitSet()),
          Send(Quote(GInt(5)), List(GInt(3)), true, BitSet())
        ))
    val result = ParSortMatcher.sortMatch(parExpr)
    result.term should be(sortedParExpr.get)
  }

  "Par" should "Sort Receives based on persistence, channels, patterns and then body" in {
    val parExpr =
      p.copy(
        receives = List(
          Receive(List(ReceiveBind(List(Quote(GInt(1))), Quote(GInt(3)))),
                  Par(),
                  false,
                  0,
                  BitSet()),
          Receive(
            List(ReceiveBind(List(Quote(GInt(0))), Quote(GInt(3)))),
            EVar(BoundVar(0)),
            false,
            0,
            BitSet()
          ),
          Receive(List(ReceiveBind(List(Quote(GInt(0))), Quote(GInt(3)))),
                  Par(),
                  false,
                  0,
                  BitSet()),
          Receive(List(ReceiveBind(List(Quote(GInt(0))), Quote(GInt(3)))),
                  Par(),
                  true,
                  0,
                  BitSet()),
          Receive(List(ReceiveBind(List(Quote(GInt(100))), Quote(GInt(2)))),
                  Par(),
                  false,
                  0,
                  BitSet())
        ))
    val sortedParExpr: Option[Par] =
      p.copy(
        receives = List(
          Receive(List(ReceiveBind(List(Quote(GInt(100))), Quote(GInt(2)))),
                  Par(),
                  false,
                  0,
                  BitSet()),
          Receive(List(ReceiveBind(List(Quote(GInt(0))), Quote(GInt(3)))),
                  Par(),
                  false,
                  0,
                  BitSet()),
          Receive(
            List(ReceiveBind(List(Quote(GInt(0))), Quote(GInt(3)))),
            EVar(BoundVar(0)),
            false,
            0,
            BitSet()
          ),
          Receive(List(ReceiveBind(List(Quote(GInt(1))), Quote(GInt(3)))),
                  Par(),
                  false,
                  0,
                  BitSet()),
          Receive(List(ReceiveBind(List(Quote(GInt(0))), Quote(GInt(3)))), Par(), true, 0, BitSet())
        ))
    val result = ParSortMatcher.sortMatch(parExpr)
    result.term should be(sortedParExpr.get)
  }

  "Par" should "Sort Match based on their value and then cases" in {
    val parMatch =
      p.copy(
        matches = List(
          Match(GInt(5), List(MatchCase(GInt(5), GInt(5)), MatchCase(GInt(4), GInt(4))), BitSet()),
          Match(GBool(true),
                List(MatchCase(GInt(5), GInt(5)), MatchCase(GInt(4), GInt(4))),
                BitSet()),
          Match(GBool(true),
                List(MatchCase(GInt(4), GInt(4)), MatchCase(GInt(3), GInt(3))),
                BitSet())
        ))
    val sortedParMatch: Option[Par] =
      p.copy(
        matches = List(
          Match(GBool(true),
                List(MatchCase(GInt(4), GInt(4)), MatchCase(GInt(3), GInt(3))),
                BitSet()),
          Match(GBool(true),
                List(MatchCase(GInt(5), GInt(5)), MatchCase(GInt(4), GInt(4))),
                BitSet()),
          Match(GInt(5), List(MatchCase(GInt(5), GInt(5)), MatchCase(GInt(4), GInt(4))), BitSet())
        ))
    val result = ParSortMatcher.sortMatch(parMatch)
    result.term should be(sortedParMatch.get)
  }

  "Par" should "Sort EVars based on their type and then levels" in {
    val parGround =
      p.copy(exprs = List(EVar(FreeVar(2)), EVar(FreeVar(1)), EVar(BoundVar(2)), EVar(BoundVar(1))))
    val sortedParGround: Option[Par] =
      p.copy(exprs = List(EVar(BoundVar(1)), EVar(BoundVar(2)), EVar(FreeVar(1)), EVar(FreeVar(2))))
    val result = ParSortMatcher.sortMatch(parGround)
    result.term should be(sortedParGround.get)
  }

  "Par" should "Sort exprs in order of ground, vars, arithmetic, comparisons, logical" in {
    val parExpr =
      p.copy(
        exprs = List(
          EEq(GInt(4), GInt(3)),
          EDiv(GInt(1), GInt(5)),
          EVar(BoundVar(1)),
          EOr(GBool(false), GBool(true)),
          GInt(1)
        ))
    val sortedParExpr: Option[Par] =
      p.copy(
        exprs = List(
          GInt(1),
          EVar(BoundVar(1)),
          EDiv(GInt(1), GInt(5)),
          EEq(GInt(4), GInt(3)),
          EOr(GBool(false), GBool(true))
        ))
    val result = ParSortMatcher.sortMatch(parExpr)
    result.term should be(sortedParExpr.get)
  }

  it should "sort expressions inside bundle" in {
    val parExpr =
      p.copy(
        exprs = List(
          EEq(GInt(4), GInt(3)),
          EDiv(GInt(1), GInt(5)),
          EVar(BoundVar(1)),
          EOr(GBool(false), GBool(true)),
          GInt(1)
        ))
    val sortedParExpr: Par =
      p.copy(
        exprs = List(
          GInt(1),
          EVar(BoundVar(1)),
          EDiv(GInt(1), GInt(5)),
          EEq(GInt(4), GInt(3)),
          EOr(GBool(false), GBool(true))
        ))

    val bundle = Bundle(parExpr)
    val result = BundleSortMatcher.sortMatch(bundle)
    result.term should be(Bundle(sortedParExpr))
  }

  it should "sort expressions in nested bundles preserving polarities" in {
    val parExpr =
      p.copy(
        exprs = List(
          EEq(GInt(4), GInt(3)),
          EDiv(GInt(1), GInt(5)),
          EVar(BoundVar(1)),
          EOr(GBool(false), GBool(true)),
          GInt(1)
        ))
    val sortedParExpr: Par =
      p.copy(
        exprs = List(
          GInt(1),
          EVar(BoundVar(1)),
          EDiv(GInt(1), GInt(5)),
          EEq(GInt(4), GInt(3)),
          EOr(GBool(false), GBool(true))
        ))

    val nestedBundle = Bundle(
      Bundle(Bundle(parExpr, writeFlag = true, readFlag = false),
             writeFlag = false,
             readFlag = true))
    val result = BundleSortMatcher.sortMatch(nestedBundle)
    result.term should be(
      Bundle(
        Bundle(Bundle(sortedParExpr, writeFlag = true, readFlag = false),
               writeFlag = false,
               readFlag = true)))
  }

  it should "sort logical connectives in \"not\", \"and\", \"or\" order" in {
    val parExpr =
      p.copy(
        connectives = List(
          Connective(
            ConnAndBody(ConnectiveBody(
              List(EVar(FreeVar(0)), Send(ChanVar(FreeVar(1)), List(EVar(FreeVar(2))), false))))),
          Connective(
            ConnOrBody(ConnectiveBody(List(New(1, EVar(Wildcard(Var.WildcardMsg()))),
                                           New(2, EVar(Wildcard(Var.WildcardMsg()))))))),
          Connective(VarRefBody(VarRef(0, 2))),
          Connective(ConnNotBody(Par()))
        ),
        connectiveUsed = true
      )
    val sortedParExpr: Option[Par] =
      p.copy(
        connectives = List(
          Connective(ConnNotBody(Par())),
          Connective(
            ConnAndBody(ConnectiveBody(
              List(EVar(FreeVar(0)), Send(ChanVar(FreeVar(1)), List(EVar(FreeVar(2))), false))))),
          Connective(
            ConnOrBody(ConnectiveBody(List(New(1, EVar(Wildcard(Var.WildcardMsg()))),
                                           New(2, EVar(Wildcard(Var.WildcardMsg()))))))),
          Connective(VarRefBody(VarRef(0, 2)))
        ),
        connectiveUsed = true
      )
    val result = ParSortMatcher.sortMatch(parExpr)
    result.term should be(sortedParExpr.get)
  }
}