package mlang.compiler

import mlang.compiler.Layer.Layers
import mlang.utils.{Name, debug}

import scala.collection.mutable



sealed trait ElaboratorContextBuilderException extends CompilerException

object ElaboratorContextBuilderException {

  
}



object ElaboratorContextBuilder {
  private val gen = new LongGen.Positive()
  private val dgen = new LongGen.Positive()
}

import ElaboratorContextBuilder._

trait ElaboratorContextBuilder extends ElaboratorContextWithMetaOps {

  type Self <: ElaboratorContextBuilder

  protected implicit def create(a: Layers): Self

  def drop(up: Int): Self = create(layers.drop(up))

  def newSyntaxDirectedRestrictionLayer(pair: Value.Formula.Assignments): Self = {
    debug(s"new syntax directed layer $pair")
    Layer.Restriction(0, pair, createMetas()) +: layers
  }

  def newReifierRestrictionLayer(pair: Value.Formula): Self = {
    Layer.ReifierRestriction(createMetas()) +: layers
  }

  def newDimensionLayer(n: Name, dimension: Value.Formula.Generic): Self = {
    val l = Layer.Dimension(dimension, n, createMetas())
    val ctx: Self = l +: layers
    ctx
  }

  def newDimensionLayer(n: Name): (Self, Value.Formula.Generic) = {
    val v = Value.Formula.Generic(dgen())
    val l = Layer.Dimension(v, n, createMetas())
    val ctx: Self = l +: layers
    (ctx, v)
  }

  def lookupDefined(a: Name): Option[(Int, Value, Boolean)] = layers.head match {
    case Layer.Defines(_, defines) =>
      val index = defines.indexWhere(_.name.intersect(a))
      if (index < 0) {
        None
      } else {
        val ds = defines(index)
        Some((index, ds.typ, ds.isDefined))
      }
    case _ => logicError()
  }

  def newDefinesLayer(): Self = Layer.Defines(createMetas(), Seq.empty) +: layers

  def newDefinition(name: Name, typ: Value, v: Value.Reference) : (Self, Int, Value.Generic) = {
    layers.head match {
      case Layer.Defines(metas, defines) =>
        defines.find(_.name.intersect(name)) match {
          case Some(_) => logicError()
          case _ =>
            val g = Value.Generic(gen(), typ)
            (Layer.Defines(metas, defines :+ DefineItem(ParameterBinder(name, g), Some(v))) +: layers.tail, defines.size, g)
        }
      case _ => logicError()
    }
  }

  def newDefinitionChecked(index: Int, name: Name, v: Value.Reference) : Self = {
    layers.head match {
      case Layer.Defines(metas, defines) =>
        defines(index) match {
          case DefineItem(typ0, None) =>
            assert(typ0.name == name)
            Layer.Defines(metas, defines.updated(index, DefineItem(typ0, Some(v)))) +: layers.tail
          case _ =>
            logicError()
        }
      case _ => logicError()
    }
  }

  def newDeclaration(name: Name, typ: Value) : (Self, Int, Value.Generic) = {
    layers.head match {
      case Layer.Defines(metas, defines) =>
        defines.find(_.name.intersect(name)) match {
          case Some(_) => logicError()
          case _ =>
            val g = Value.Generic(gen(), typ)
            val p = ParameterBinder(name, g)
            (Layer.Defines(metas, defines :+ DefineItem(p, None)) +: layers.tail, defines.size, g)
        }
      case _ => logicError()
    }
  }


//  def newDefinition(name: Name, typ: Value, v: Value): Self = {
//    layers.head.find(_.name.intersect(name)) match {
//      case Some(_) => throw ContextBuilderException.AlreadyDeclared()
//      case _ => (layers.head :+ Binder(gen(), name, typ, true, Some(v))) +: layers.tail
//    }
//  }


  def newParameterLayer(name: Name, typ: Value): (Self, Value) = {
    val g = Value.Generic(gen(), typ)
    (Layer.Parameter(ParameterBinder(name, g), createMetas()) +: layers, g)
  }

  def newParameterLayerProvided(name: Name, g: Value.Generic): Self = {
    Layer.Parameter(ParameterBinder(name, g), createMetas()) +: layers
  }



  def newParametersLayer(): Self = Layer.ParameterGraph(Seq.empty, createMetas()) +: layers


  def newParameter(name: Name, typ: Value) : (Self, Value) = {
    layers.head match {
      case Layer.ParameterGraph(binders, metas) =>
        binders.find(_.name.intersect(name)) match {
          case Some(_) => logicError()
          case _ =>
            val g = gen()
            val v = Value.Generic(g, typ)
            assert(metas.debug_allFrozen)
            (Layer.ParameterGraph(
              binders :+ ParameterBinder(name, v), metas) +: layers.tail, v)
        }
      case _ => logicError()
    }
  }



  def newPatternLayer(pattern: Concrete.Pattern, typ: Value): (Self, Value, Pattern) = {
    val vvv = mutable.ArrayBuffer[ParameterBinder]()
    def recs(maps: Seq[Concrete.Pattern], nodes: Value.ClosureGraph): Seq[(Value, Pattern)] = {
      var vs =  Seq.empty[(Value, Pattern)]
      var graph = nodes
      for (i <- maps.indices) {
        val tv = rec(maps(i), graph(i).independent.typ)
        graph = Value.ClosureGraph.reduce(graph, vs.size, tv._1)
        vs = vs :+ tv
      }
      vs
    }

    def rec(p: Concrete.Pattern, t: Value): (Value, Pattern) = {
      p match {
        case Concrete.Pattern.Atom(name) =>
          var ret: (Value, Pattern) = null
          var index = 0
          name.asRef match {
            case Some(ref) =>
              t.whnf match {
                case sum: Value.Sum if { index = sum.constructors.indexWhere(c => c.name.by(ref) && c.nodes.isEmpty); index >= 0 } =>
                  ret = (Value.Maker(t, index), Pattern.Construct(index, Seq.empty))
                case _ =>
              }
            case _ =>
          }
          if (ret == null) {
            val open = ParameterBinder(name, Value.Generic(gen(), t))
            vvv.append(open)
            ret = (open.value, Pattern.Atom)
          }
          ret
        case Concrete.Pattern.Group(maps) =>
          t.whnf match {
            case r: Value.Record =>
              if (maps.size == r.nodes.size) {
                val vs = recs(maps, r.nodes)
                (Value.Apps(Value.Maker(t, -1), vs.map(_._1)), Pattern.Make(vs.map(_._2)))
              } else {
                throw PatternExtractException.MakeWrongSize()
              }
            case _ => throw PatternExtractException.MakeIsNotRecordType()
          }
        case Concrete.Pattern.NamedGroup(name, maps) =>
          t.whnf match {
            case sum: Value.Sum =>
              val index = sum.constructors.indexWhere(_.name.by(name))
              if (index >= 0) {
                val c = sum.constructors(index)
                if (c.nodes.size == maps.size) {
                  val vs = recs(maps, c.nodes)
                  (Value.Apps(Value.Maker(t, index), vs.map(_._1)), Pattern.Construct(index, vs.map(_._2)))
                } else {
                  throw PatternExtractException.ConstructWrongSize()
                }
              } else {
                throw PatternExtractException.ConstructUnknownName()
              }
            case _ => throw PatternExtractException.ConstructNotSumType()
          }
      }
    }
    val (os, p) = rec(pattern, typ)
    val ctx: Self = Layer.PatternParameters(vvv.toSeq, createMetas()) +: layers
    (ctx, os, p)
  }
}
