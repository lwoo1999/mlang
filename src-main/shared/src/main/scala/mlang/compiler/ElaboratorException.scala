package mlang.compiler



sealed trait ElaboratorException extends CompilerException

object ElaboratorException {
  // syntax
  case class FieldsDuplicated() extends ElaboratorException
  case class TagsDuplicated() extends ElaboratorException
  case class MustBeNamed() extends ElaboratorException
  case class EmptyTelescope() extends ElaboratorException
  case class EmptyArguments() extends ElaboratorException

  // elimination mismatch
  case class UnknownAsType() extends ElaboratorException
  case class UnknownProjection() extends Exception(s"Unknown projection") with ElaboratorException
  case class UnknownAsFunction() extends ElaboratorException


  case class CannotInferLambda() extends ElaboratorException
  case class CannotInferReturningTypeWithPatterns() extends ElaboratorException

  case class CannotInferObjectNow()  extends ElaboratorException


  case class TypeMismatch() extends ElaboratorException

  case class ForbiddenModifier() extends ElaboratorException

  case class DeclarationWithoutDefinition() extends ElaboratorException

  case class ExpectingFormula() extends ElaboratorException

  case class PathEndPointsNotMatching() extends ElaboratorException
  case class InferPathEndPointsTypeNotMatching() extends ElaboratorException

  case class ExpectingLambdaTerm() extends ElaboratorException

  case class CapNotMatching() extends ElaboratorException
  case class FacesNotMatching() extends ElaboratorException

  case class RequiresValidRestriction() extends ElaboratorException
  case class TermICanOnlyAppearInDomainOfFunction() extends ElaboratorException


  case class CannotInferMakeExpression() extends ElaboratorException
  case class CannotInferVMakeExpression() extends ElaboratorException

  case class VProjCannotInfer() extends ElaboratorException

  case class CannotInferMeta() extends ElaboratorException

  case class NotDefinedReferenceForTypeExpressions() extends ElaboratorException


  case class NotExpectingImplicitArgument() extends ElaboratorException

  case class RecursiveTypesMustBeDefinedAtTopLevel() extends ElaboratorException

  case class UpCanOnlyBeUsedOnTopLevelDefinitionOrUniverse()  extends ElaboratorException

  case class AlreadyDeclared() extends ElaboratorException
  case class AlreadyDefined() extends ElaboratorException
  case class NotDeclared() extends ElaboratorException
  case class SeparateDefinitionCannotHaveTypesNow() extends ElaboratorException
  case class DimensionLambdaCannotBeImplicit() extends ElaboratorException
  case class CannotInferPathTypeWithoutBody() extends ElaboratorException

  // TODO maybe we should just show a warning
  case class RemoveFalseFace() extends ElaboratorException
  case class RemoveConstantVType() extends ElaboratorException
  case class VTypeDimensionInconsistent() extends ElaboratorException

  case class VMakeMismatch() extends ElaboratorException

  case class ConstantSortWrong() extends ElaboratorException
}
