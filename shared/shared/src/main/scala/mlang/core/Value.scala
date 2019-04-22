package mlang.core

import mlang.name._

import scala.collection.mutable


sealed trait PatternExtractException extends CoreException


object PatternExtractException {
  case class MakeWrongSize() extends PatternExtractException
  case class MakeIsNotRecordType() extends PatternExtractException
  case class ConstructUnknownName() extends PatternExtractException
  case class ConstructWrongSize() extends PatternExtractException
  case class ConstructNotSumType() extends PatternExtractException
}

import Value._

sealed trait Value {


  final override def equals(obj: Any): Boolean = {
    throw new IllegalArgumentException("Values don't have equal. Either call eq or do conversion checking")
  }

  private var _whnf: Value = _
  private var _nf: Value = _

  // LATER what's the relationship between whnf and reduction class?
  // reduction mainly used to determine the way a closure is evaluated
  // a value itself that contains redux can be evaluated by whnf and normalize two method
  def whnf: Value = {
    if (_whnf == null) {
      _whnf = this match {
        case _: HeadCanonical =>
          this
        case _: Reference =>
          this.deref(Reduction.Deref.All).whnf
        case _: OpenReference =>
          this
        case Application(lambda, argument) =>
          val wh = lambda.whnf
          if (wh.eq(lambda)) {
            this
          } else {
            wh.app(argument, Reduction.App.Once, true)
          }
        case Projection(make, field) =>
          val wh = make.whnf
          if (wh.eq(make)) {
            this
          } else {
            wh.project(field, Reduction.Project, true)
          }
        case PatternStuck(lambda, stuck) =>
          val wh = stuck.whnf
          if (wh.eq(stuck)) {
            this
          } else {
            lambda.app(wh, Reduction.App.Once, true)
          }
        case Maker(r, i) =>
          // this is a lambda or make expression so in whnf
          r.whnf.demaker(i, Reduction.Normalize)
        case Let(_, _, body) =>
          body.whnf
        case PathApplication(left, stuck) =>
          left.whnf.papp(stuck, Reduction.Papp.Once, true)
      }
      _whnf._whnf = _whnf
    }
    _whnf
  }

  def renormalize(r: Reduction): Value = {
    if (r.renormalize) {
      this.normalize
    } else {
      this
    }
  }


  private def normalizeWhnfStuck(a: Value): Value = {
    a match {
      case OpenReference(id, typ) =>
        OpenReference(id, typ.normalize)
      case Application(lambda, argument) =>
        Application(normalizeWhnfStuck(lambda), argument.normalize)
      case Projection(make, field) =>
        Projection(normalizeWhnfStuck(make), field)
      case PatternStuck(lambda, stuck) =>
        PatternStuck(lambda, normalizeWhnfStuck(stuck))
      case PathApplication(left, stuck) =>
        PathApplication(left.normalize, stuck)
      case _ => logicError()
    }
  }

  def normalize: Value = {
    if (_nf == null) {
      _nf = this.whnf match {
        case a: Universe =>
          a
        case Function(domain, codomain) =>
          Function(domain.normalize, codomain)
        case r:Record =>
          r
        case s: Sum =>
          s
        case p: PatternLambda =>
          p
        case l: Lambda =>
          l
        case p: PathLambda =>
          p
        case Make(values) =>
          Make(values.map(_.normalize))
        case Construct(name, vs) =>
          Construct(name, vs.map(_.normalize))
        case PathType(typ, left, right) =>
          PathType(typ, left.normalize, right.normalize)
        case _: Reference => logicError()
        case _: Maker => logicError()
        case _: Let => logicError()
        case a =>
          normalizeWhnfStuck(a)
      }
      _nf._nf = _nf
    }
    _nf
  }


  // also used to decide how
  def app(v: Value, env: Reduction /* REDUCTION */, whnf: Boolean = false): Value = env.app.map(r => {
    this match {
      case Lambda(closure) =>
        val ret = closure(v, r)
        if (whnf) ret.whnf else ret
      case p@PatternLambda(_, _, cases) =>
        // TODO overlapping patterns, we are now using first match
        var res: Value = null
        var cs = cases
        while (cs.nonEmpty && res == null) {
          res = cs.head.tryApp(v, r).orNull
          cs = cs.tail
        }
        if (res != null) {
          if (whnf) res.whnf else res
        } else {
          PatternStuck(p, v)
        }
      case _ =>
        Application(this, v)
    }
  }).getOrElse(Application(this, v))


  def papp(d: Dimension, env: Reduction /* REDUCTION */, whnf: Boolean = false): Value = env.papp.map(r => {
    this match {
      case PathLambda(c) =>
        val res = c(d, r)
        if (whnf) res.whnf else res
      case a =>
        d match {
          case Dimension.Constant(i) =>
            infer(a, env).whnf match {
              case PathType(_, left, right) =>
                val res = if (i) right else left
                if (whnf) res.whnf else res
              case _ => logicError()
            }
          case _: Dimension.OpenReference =>
            PathApplication(this, d)
        }
    }
  }).getOrElse(PathApplication(this, d))


  def makerType(i: Int): Value = this.whnf match {
    case s: Sum => s.constructors(i).makerType
    case v: Record => v.makerType
    case _ => logicError()
  }

  def demaker(i: Int, env: Reduction /* REDUCTION */): Value = if (env.demaker) {
    this match {
      case s: Sum => s.constructors(i).maker
      case r: Record =>
        assert(i == -1)
        r.maker
      case _ => Maker(this, i)
    }
  } else {
    Maker(this, i)
  }

  def project(name: Int, env: Reduction /* REDUCTION */, whnf: Boolean = false): Value = if (env.project) {
    this match {
      case Make(vs) => vs(name).whnf
      case _ => Projection(this, name)
    }
  } else {
    Projection(this, name)
  }

  def delet(env: Reduction /* REDUCTION */): Value =
    if (env.delet) {
      this match {
        case v: Let => v.body.delet(env)
        case _ => this
      }
    } else {
      this
    }

  def deref(env: Reduction /* REDUCTION */): Value =
    if (env.deref == Deref.Normalize) {
      this match {
        case v: Reference => v.value.deref(env).normalize
        case o: OpenReference => o.copy(typ = o.typ.normalize)
        case _ => this
      }
    } else if (env.deref == Deref.All) {
      this match {
        case v: Reference => v.value.deref(env)
        case _ => this
      }
    } else if (env.deref == Deref.NonRecursive) {
      this match {
        case v: Reference if v.closed == 0 => v.value.deref(env)
        case _ => this
      }
    } else {
      this
    }
}


object Value {

  implicit class MultiClosure(private val func: (Seq[Value], Reduction) => Value) extends AnyVal {
    def apply(seq: Seq[Value], reduction: Reduction /* REDUCTION */): Value = func(seq, reduction)
  }

  implicit class Closure(private val func: (Seq[Value], Reduction) => Value) extends AnyVal {
    def apply(seq: Value, reduction: Reduction /* REDUCTION */): Value = func(Seq(seq), reduction)
  }

  object Closure {
    def apply(a: Value): Closure = Closure((_, r) => a.renormalize(r))
  }

  implicit class PathClosure(private val func: (Dimension, Reduction) => Value) extends AnyVal {
    def apply(seq: Dimension, reduction: Reduction /* REDUCTION */): Value = func(seq, reduction)
  }


  sealed trait Dimension
  object Dimension {
    case class OpenReference(id: Long) extends Dimension
    case class Constant(isOne: Boolean) extends Dimension
  }

  type StuckPos = Value
  
  sealed trait HeadCanonical extends Value

  case class Reference(value: Value, closed: Int) extends Value
  case class OpenReference(id: Generic, typ: Value) extends Value

  case class Universe(level: Int) extends HeadCanonical

  case class Function(domain: Value, codomain: Closure) extends HeadCanonical
  case class Application(lambda: StuckPos, argument: Value) extends Value

  // TODO should have a field: recursive, and it must be recursive
  // TODO record should have a type

  case class RecordNode(name: Name, dependencies: Seq[Int], closure: MultiClosure)

  case class Record(level: Int, nodes: Seq[RecordNode]) extends HeadCanonical {
    assert(nodes.isEmpty || nodes.head.dependencies.isEmpty)

    def rthis(r: Reduction): Record = this // Reference(this).deref(r)

    lazy val maker: Value = {
      def rec(known: Seq[Value], remaining: Seq[RecordNode], r: Reduction): Value = {
        remaining match {
          case Seq() => Make(known)
          case _ +: tail =>
            Lambda(Closure((p, r) => rec(known :+ p.head, tail, r)))
        }
      }
      rec(Seq.empty, nodes, Reduction.No)
    }

    lazy val makerType: Value = {
      def rec(known: Seq[Value], remaining: Seq[RecordNode], r: Reduction): Value = {
        remaining match {
          case Seq() => rthis(r)
          case Seq(head) =>
            Function(head.closure(head.dependencies.map(known), r),
              Closure((_, r) => rthis(r)))
          case head +: more +: tail =>
            Function(head.closure(head.dependencies.map(known), r), Closure((p, r) => {
              rec(known ++ Seq(p.head), tail, r)
            }))
        }
      }
      rec(Seq.empty, nodes, Reduction.No)
    }
    def projectedType(values: Seq[Value], name: Int, reduction: Reduction /* REDUCTION */): Value = {
      val b = nodes(name)
      b.closure(b.dependencies.map(values), reduction)
    }
  }

  case class Make(values: Seq[Value]) extends HeadCanonical

  case class Maker(value: StuckPos, field: Int) extends Value

  case class Projection(make: StuckPos, field: Int) extends Value

  case class Construct(name: Tag, vs: Seq[Value]) extends HeadCanonical
  // TODO sum should have a type, it can be indexed, so a pi type ends with type_i
  // TODO should have a field: recursive, and it must be recursive, also in case of indexed, use Constructor instead of value
  case class Constructor(name: Tag, parameters: Int, nodes: Seq[MultiClosure]) {
    private[Value] var _sum: Sum = _
    def rthis(r: Reduction): Sum = _sum // Reference(_sum).deref(r)

    lazy val maker: Value = {
      def rec(known: Seq[Value], remaining: Seq[MultiClosure], r: Reduction): Value = {
        remaining match {
          case Seq() => Construct(name, known)
          case _ +: tail =>
            Lambda(Closure((p, r) => rec(known :+ p.head, tail, r)))
        }
      }
      rec(Seq.empty, nodes, Reduction.No)
    }

    lazy val makerType: Value = {
      def rec(known: Seq[Value], remaining: Seq[MultiClosure], r: Reduction): Value = {
        remaining match {
          case Seq() => rthis(r)
          case Seq(head) =>
            Function(head(known, r), Closure((_, r) => rthis(r)))
          case head +: _ +: tail =>
            Function(head(known, r), Closure((p, r) => {
              rec(known :+ p.head, tail, r)
            }))
        }
      }
      rec(Seq.empty, nodes, Reduction.No)
    }
  }
  
  case class Sum(level: Int, constructors: Seq[Constructor]) extends HeadCanonical {
    for (c <- constructors) {
      c._sum = this
    }
  }

  case class Case(pattern: Pattern, closure: MultiClosure) {
    def tryApp(v: Value, r: Reduction): Option[Value] = {
      extract(pattern, v).map(a => closure(a, r))
    }
  }

  /**
    * this lambda is transparent on the arguments
    */
  case class Lambda(closure: Closure) extends HeadCanonical

  case class PatternLambda(id: Generic, typ: Closure, cases: Seq[Case]) extends HeadCanonical

  case class PatternStuck(lambda: PatternLambda, stuck: StuckPos) extends Value

  case class Let(var items: Seq[Value], order: Seq[Int], body: Value) extends Value


  case class PathType(typ: PathClosure, left: Value, right: Value) extends HeadCanonical

  case class PathLambda(body: PathClosure) extends HeadCanonical

  // even when left is a value, it will not stuck, because only open dimension can stuck
  case class PathApplication(left: Value, stuck: Dimension) extends Value


  private val gen = new GenericGen.Negative()

  def inferLevel(t1: Value): Int = infer(t1, Reduction.Normalize) match {
    case Universe(l) => l
    case _ => logicError()
  }

  // only works for values of path type and universe types
  def infer(t1: Value, r: Reduction): Value = {
    t1.whnf match {
      case OpenReference(_, v1) =>
        v1
      case Universe(level) => Universe(level + 1)
      case Function(domain, codomain) =>
        (infer(domain, r), infer(codomain(OpenReference(gen(), domain), r), r)) match {
          case (Universe(l1), Universe(l2)) => Universe(l1 max l2)
          case _ => logicError()
        }
      case Record(level, _) =>
        Universe(level)
      case Sum(level, _) =>
        Universe(level)
      case PathType(typ, _, _) =>
        infer(typ.apply(Dimension.OpenReference(gen()), r), r)
      case Application(l1, a1) =>
        infer(l1, r).whnf match {
          case Function(_, c) =>
            c(a1, r)
          case _ => logicError()
        }
      case Projection(m1, f1) =>
        infer(m1, r).whnf match {
          case rr: Record  => rr.projectedType(rr.nodes.indices.map(n => Projection(m1, n)), f1, r)
          case _ => logicError()
        }
      case PatternStuck(l1, s1) =>
        l1.typ(s1, r)
      case PathApplication(l1, d1) =>
        infer(l1, r).whnf match {
          case PathType(typ, _, _) =>
            typ(d1, r)
          case _ => logicError()
        }
      case _ => logicError()
    }
  }



  def extract(pattern: Pattern, v: Value): Option[Seq[Value]] = {
    val vs = mutable.ArrayBuffer[Value]()
    def rec(pattern: Pattern, v: Value): Boolean = {
      pattern match {
        case Pattern.Atom =>
          vs.append(v)
          true
        case Pattern.Make(names) =>
          v.whnf match {
            case Make(values) =>
              names.zip(values).forall(pair => rec(pair._1, pair._2))
            case _ =>
              false
          }
        case Pattern.Construct(name, pt) =>
          v.whnf match {
            case Construct(n, values) if name == n =>
              pt.zip(values).forall(pair => rec(pair._1, pair._2))
            case _ =>
              false
          }
      }
    }
    if (rec(pattern, v)) {
      Some(vs)
    } else {
      None
    }
  }
}

