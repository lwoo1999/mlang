package mlang.concrete

import mlang.name._


sealed trait Term

case class NameType(names: Seq[Name], ty: Term)

object NameType {
  type FlatSeq = Seq[(Name.Opt, Term)]

  def flatten(names: Seq[NameType]): NameType.FlatSeq = names.flatMap(n => {
    if (n.names.isEmpty) {
      Seq((None, n.ty))
    } else {
      n.names.map(m => (Some(m), n.ty))
    }
  })
}

sealed trait Block

object Term {

  // TODO don't have special syntax for this?
  case class Universe(level: Int) extends Term

  case class Reference(name: Ref) extends Term // some name is renamed

  case class Cast(term: Term, typ: Term) extends Term

  case class Function(domain: Seq[NameType], codomain: Term) extends Term

  case class Record(fields: Seq[NameType]) extends Term {
    val names = fields.flatMap(_.names)
  }

  case class Constructor(name: Tag, term: Seq[NameType])
  case class Sum(constructors: Seq[Constructor]) extends Term with Block

  case class Application(left: Term, right: Seq[Term]) extends Term

  case class Projection(left: Term, right: Ref) extends Term


  case class Case(pattern: Pattern, body: Term)
  case class PatternLambda(branches: Seq[Case]) extends Term
  case class Lambda(name: Name.Opt, body: Term) extends Term

  // TODO can you define a macro in a abstracted context?
  case class Let(declarations: Seq[Declaration], in: Term) extends Term with Block

  // TODO case class Object() big syntax with define and stuff
  // TODO make small syntax with various stuff, type is inferred as non-dependent
  // TODO local match and local tree split

  case class PathLambda(name: Name.Opt, body: Term) extends Term
  case class PathType(typ: Option[(Name.Opt, Term)], left: Term, right: Term) extends Term
  case class PathApplication(let: Term, r: Term) extends Term
  case class ConstantDimension(isOne: Boolean) extends Term

  case class DimensionPair(from: Term, to: Term)
  case class Restriction(dimension: DimensionPair, term: Term)
  case class Coe(direction: DimensionPair, typ: (Name.Opt, Term), base: Term) extends Term
  case class Hcom(direction: DimensionPair, typ: Term, base: Term, restrictions: Seq[Restriction]) extends Term
}

case class Module(declarations: Seq[Declaration])




sealed trait Declaration {
  def modifiers: Seq[Declaration.Modifier]
}


object Declaration {
  sealed trait Modifier
  object Modifier {
    case object WithConstructor extends Modifier
    case object Inductively extends Modifier
    case object Ignored extends Modifier
    case object __Debug extends Modifier
  }
  case class Define(modifiers: Seq[Modifier],name: Name, parameters: Seq[NameType], typ: Option[Term], term: Term) extends Declaration
  // depending on our algorithm, recursive ones might not need to declare first
  case class Declare( modifiers: Seq[Modifier],name: Name, parameters: Seq[NameType], typ: Term) extends Declaration
}





sealed trait Pattern

object Pattern {
  case class Atom(id: Name.Opt) extends Pattern
  case class Group(names: Seq[Pattern]) extends Pattern // TODO named patterns?
  case class NamedGroup(name: Ref, pattern: Seq[Pattern]) extends Pattern
}