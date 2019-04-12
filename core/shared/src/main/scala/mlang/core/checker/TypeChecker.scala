package mlang.core.checker

import mlang.core.concrete.{Pattern => Patt, _}
import Context._
import mlang.core.concrete.Declaration.Modifier
import mlang.core.name._
import mlang.core.utils
import mlang.core.utils.debug

import scala.collection.mutable
import scala.language.implicitConversions




sealed trait TypeCheckException extends CoreException

object TypeCheckException {


  // names
  case class NamesDuplicated() extends TypeCheckException
  case class MustBeNamed() extends TypeCheckException
  case class EmptyTelescope() extends TypeCheckException
  case class EmptyArguments() extends TypeCheckException
  case class EmptyLambdaParameters() extends TypeCheckException

  // elimination mismatch
  case class UnknownAsType() extends TypeCheckException
  case class UnknownProjection() extends TypeCheckException
  case class UnknownAsFunction() extends TypeCheckException

  case class CheckingAgainstNonFunction() extends TypeCheckException

  case class CannotInferLambdaWithoutDomain() extends TypeCheckException

  case class TypeMismatch() extends TypeCheckException

  case class CannotInferReturningTypeWithPatterns() extends TypeCheckException

  case class ForbiddenModifier() extends TypeCheckException

  case class DeclarationWithoutDefinition(name: Name) extends TypeCheckException
}




class TypeChecker private (protected override val layers: Layers) extends ContextBuilder with Conversion with BaseEvaluator with PlatformEvaluator {
  override type Self = TypeChecker

  override protected implicit def create(a: Layers): Self = new TypeChecker(a)


  private def infer(term: Term): (Value, Abstract) = {
    debug(s"infer $term")
    val res = term match {
      case Term.Universe(i) =>
        (Value.Universe(i + 1), Abstract.Universe(i))
      case Term.Reference(name) =>
        // should lookup always return a value? like a open reference?
        val (Binder(_, _, t, _, _), abs) = lookup(name)
        (t, abs)
      case Term.Cast(v, t) =>
        val (_, ta) = inferLevel(t)
        val tv = eval(ta)
        (tv, check(v, tv))
      case Term.Function(domain, codomain) =>
        if (domain.isEmpty) throw TypeCheckException.EmptyTelescope()
        val (l, v) = inferFunction(NameType.flatten(domain), codomain)
        (Value.Universe(l), v)
      case r@Term.Record(fields) =>
        // TODO calculate real record dependencies
        for (f <- fields) {
          if (f.names.isEmpty) throw TypeCheckException.MustBeNamed()
        }
        for (i <- r.names.indices) {
          for (j <- (i + 1) until r.names.size) {
            if (r.names(i) intersect r.names(j)) {
              throw TypeCheckException.NamesDuplicated()
            }
          }
        }
        val (fl, fs) = newLayer().inferLevel(fields)
        (Value.Universe(fl), Abstract.Record(fl, fs.map(pair => Abstract.RecordNode(pair._1, pair._2))))
      case Term.Sum(constructors) =>
        for (i <- constructors.indices) {
          for (j <- (i + 1) until constructors.size) {
            if (constructors(i).name == constructors(j).name) {
              throw TypeCheckException.NamesDuplicated()
            }
          }
        }
        // TODO in case of HIT, each time a constructor finished, we need to construct a partial sum and update the value
        val fs = constructors.map(c => newLayer().newLayer().inferLevel(c.term))
        val fl = fs.map(_._1).max
        (Value.Universe(fl), Abstract.Sum(fl, fs.map(_._2.map(_._2)).zip(constructors).map(a => Abstract.Constructor(a._2.name, a._1))))
      case Term.PatternLambda(_) =>
        // TODO inferring the type of a lambda, the inferred type might not have the same branches as the lambda itself
        throw TypeCheckException.CannotInferLambdaWithoutDomain()
      case Term.Lambda(_, _) =>
        // TODO inferring the type of a lambda, the inferred type might not have the same branches as the lambda itself
        throw TypeCheckException.CannotInferLambdaWithoutDomain()
      case Term.Projection(left, right) =>
        val (lt0, la) = infer(left)
        val lt = lt0.deRecursiveHead()
        val lv = eval(la).deRecursiveHead()
        def ltr = lt.asInstanceOf[Value.Record]
        def error() = throw TypeCheckException.UnknownProjection()
        lv match {
          case m: Value.Make if ltr.nodes.exists(_.name.by(right)) =>
            val index = ltr.nodes.indexWhere(_.name.by(right))
            (ltr.projectedType(m.values, index), Abstract.Projection(la, index))
          // TODO user defined projections
          case r: Value.Record if right == Ref.make =>
            (r.makerType, Abstract.RecordMaker(la))
          case r: Value.Sum if r.constructors.exists(_.name == right) =>
            r.constructors.find(_.name == right) match {
              case Some(br) =>
                (br.makerType, Abstract.SumMaker(la, r.constructors.indexWhere(_.name == right)))
              case _ => error()
            }
          case _ => error()
        }
      case Term.Application(lambda, arguments) =>
        if (arguments.size == 0) throw TypeCheckException.EmptyArguments()
        val (lt, la) = infer(lambda)
        inferApplication(lt, la, arguments)
      case Term.Let(declarations, in) =>
        val (ctx, da, order) = newLayer().checkDeclarations(declarations)
        val (it, ia) = ctx.infer(in)
        (it, Abstract.Let(da, order, ia))
    }
    debug(s"infer result ${res._2}")
    res
  }

  private def inferFunction(domain: NameType.FlatSeq, codomain: Term): (Int, Abstract) = {
    domain match {
      case head +: tail =>
        val (dl, da) = inferLevel(head._2)
        var ctx = newLayer()
        head._1 match {
          case Some(n) =>
            ctx = ctx.newAbstraction(n, eval(da))._1
          case _ =>
        }
        val (cl, ca) = ctx.inferFunction(tail, codomain)
        (dl max cl, Abstract.Function(da, ca))
      case Seq() =>
        inferLevel(codomain)
    }
  }

  private def inferApplication(@canrecur lt0: Value, la: Abstract, arguments: Seq[Term]): (Value, Abstract) = {
    val lt = lt0.deRecursiveHead()
    arguments match {
      case head +: tail =>
        lt match {
          case Value.Function(domain, codomain) =>
            val aa = check(head, domain)
            val av = eval(aa)
            val lt1 = codomain(Seq(av)).deRecursiveHead()
            val la1 = Abstract.Application(la, aa)
            inferApplication(lt1, la1, tail)
            // TODO user defined applications
          case _ => throw TypeCheckException.UnknownAsFunction()
        }
      case Seq() =>
        (lt, la)
    }
  }

  private def checkFallback(term: Term, cp: Value): Abstract = {
    val (tt, ta) = infer(term)
    if (equalType(Int.MaxValue, tt, cp)) ta
    else throw TypeCheckException.TypeMismatch()
  }

  private def check(term: Term, @canrecur cp0: Value, lambdaNameHints: Option[Seq[Name.Opt]] = None): Abstract = {
    val cp = cp0.deRecursiveHead()
    debug(s"check $term")
    val (hint, tail) = lambdaNameHints match {
      case Some(head +: tail) => (head, Some(tail))
      case _ => (None, None)
    }
    val res = term match {
      case Term.Lambda(n, body) =>
        cp match {
          case Value.Function(domain, codomain) =>
            val (ctx, v) = newLayer().newAbstraction(n.orElse(hint).getOrElse(Name.empty), domain)
            val ba = ctx.check(body, codomain(Seq(v)), tail)
            Abstract.Lambda(ba)
          case _ => throw TypeCheckException.CheckingAgainstNonFunction()
        }
      case Term.PatternLambda(cases) =>
        cp match {
          case Value.Function(domain, codomain) =>
            // TODO I think this is WRONG!!!!! how to rebind variables???
            Abstract.PatternLambda(codomain, cases.map(c => {
              val (ctx, v, pat) = newLayer().newAbstractions(c.pattern, domain)
              val ba = ctx.check(c.body, codomain(Seq(v)), tail)
              Abstract.Case(pat, ba)
            }))
          case _ => throw TypeCheckException.CheckingAgainstNonFunction()
        }
      case _ =>
        checkFallback(term, cp)
    }
    debug(s"check result $res")
    res
  }


  private def checkDeclaration(s: Declaration, abs: mutable.ArrayBuffer[DefinitionInfo]): Self = {
    /**
      * how to handle mutual recursive definitions
      */
    s match {
      case Declaration.DefineInferred(name, ms, v) =>
        val index = layers.head.indexWhere(_.name == name)
        if (index < 0) {
          val (vt, va) = infer(v)
          val ctx = newDeclaration(name, vt, ms.contains(Modifier.Inductively))
          debug(s"declared $name")
          abs.append(DefinitionInfo(name, vt, va))
          ctx
        } else {
          val b = layers.head(index)
          val va = check(v, b.typ)
          debug(s"declared $name")
          abs.update(index, DefinitionInfo(name, b.typ, va))
          this
        }
      case Declaration.Define(name, ms, t, v) =>
        debug(s"check define $name")
        val (_, ta) = inferLevel(t)
        val tv = eval(ta)
        val lambdaNameHints = t match {
          case Term.Function(d, _) =>
            Some(NameType.flatten(d).map(_._1))
          case _ => None
        }
        val ctx = newDeclaration(name, tv, ms.contains(Modifier.Inductively)) // allows recursive definitions
        val va = ctx.check(v, tv, lambdaNameHints)
        debug(s"declared $name")
        abs.append(DefinitionInfo(name, tv, va))
        ctx
      case Declaration.Declare(name, ms, t) =>
        debug(s"check declare $name")
        if (ms.nonEmpty) throw TypeCheckException.ForbiddenModifier()
        val (_, ta) = inferLevel(t)
        val tv = eval(ta)
        val ctx = newDeclaration(name, tv, ms.contains(Modifier.Inductively))
        debug(s"declared $name")
        abs.append(DefinitionInfo(name, tv, null))
        ctx
    }

  }

  private def checkDeclarations(seq: Seq[Declaration]): (Self, Seq[Abstract], Seq[Set[Int]]) = {
    var ctx = this
    val abs = new mutable.ArrayBuffer[DefinitionInfo]()
    val definitionOrder = new mutable.ArrayBuffer[Set[Int]]()
    for (s <- seq) {
      if (s.modifiers.contains(Declaration.Modifier.Ignored)) {
        ctx.checkDeclaration(s, abs.clone())
      } else {
        ctx = ctx.checkDeclaration(s, abs)
      }
      val toCompile = mutable.ArrayBuffer[Int]()
      for (i <- abs.indices) {
        val t = abs(i)
        if (t.code != null && t.value.isEmpty && t.directDependencies.forall(j => abs(j).code != null)) {
          toCompile.append(i)
        }
      }
      if (toCompile.nonEmpty) {
        val toCal = toCompile.map(i => i -> abs(i).directDependencies.filter(a => toCompile.contains(a))).toMap
        val ccc = utils.graphs.tarjanCcc(toCal).toSeq.sortWith((l, r) => {
          l.exists(ll => r.exists(rr => abs(ll).directDependencies.contains(rr)))
        }).reverse
        for (c <- ccc) {
          assert(c.nonEmpty)
          definitionOrder.append(c)
          if (c.size == 1 && !abs(c.head).directDependencies.contains(c.head)) {
            val g = abs(c.head)
            val v = ctx.eval(g.code)
            g.value = Some(v)
            ctx = ctx.newDefinitionChecked(c.head, g.name, v)
            debug(s"defined ${g.name}")
          } else {
            for (i <- c) {
              abs(i).code.markRecursive(0, c)
            }
            val vs = ctx.evalMutualRecursive(c.map(i => i -> abs(i).code).toMap)
            for (v <- vs) {
              val ab = abs(v._1)
              ab.value = Some(v._2)
              val name = ab.name
              ctx = ctx.newDefinitionChecked(v._1, name, v._2)
              debug(s"defined recursively $name")
            }
          }
        }
      }
    }
    assert(abs.size == ctx.layers.head.size)
    abs.foreach(f => {
      if (f.code == null) {
        throw TypeCheckException.DeclarationWithoutDefinition(f.name)
      }
    })
    (ctx, abs.map(_.code), definitionOrder)
  }


  private def inferLevel(terms: Seq[NameType]): (Int, Seq[(Name, Abstract)]) = {
    var ctx = this
    var l = 0
    val fas = terms.flatMap(f => {
      val fs = (if (f.names.isEmpty) Seq(Name.empty) else f.names)
      fs.map(n => {
        val (fl, fa) = ctx.inferLevel(f.ty)
        l = l max fl
        ctx = ctx.newAbstraction(n, eval(fa))._1
        (n, fa)
      })
    })
    (l, fas)
  }

  private def inferLevel(term: Term): (Int, Abstract) = {
    val (tt, ta) = infer(term)
    tt.deRecursiveHead() match {
      case Value.Universe(l) => (l, ta)
      // TODO user defined type coercion
      case _ => throw TypeCheckException.UnknownAsType()
    }
  }

  def check(m: Module): TypeChecker = checkDeclarations(m.declarations)._1
}

private case class DefinitionInfo(
    name: Name,
    typ: Value,
    code: Abstract,
    var value: Option[Value] = None,
   ) {
   lazy val directDependencies: Set[Int] = code.dependencies(0)
}

object TypeChecker {
  val empty = new TypeChecker(Seq(Seq.empty))
}
