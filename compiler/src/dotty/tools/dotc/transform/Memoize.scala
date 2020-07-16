package dotty.tools.dotc
package transform

import core._
import DenotTransformers._
import Contexts.{Context, ctx}
import SymDenotations.SymDenotation
import Denotations._
import Symbols._
import SymUtils._
import Constants._
import ast.Trees._
import MegaPhase._
import NameKinds.TraitSetterName
import NameOps._
import Flags._
import Decorators._

object Memoize {
  val name: String = "memoize"
}

/** Provides the implementations of all getters and setters, introducing
 *  fields to hold the value accessed by them.
 *  TODO: Make LazyVals a part of this phase?
 *
 *    <accessor> <stable> <mods> def x(): T = e
 *      -->  private val x: T = e
 *           <accessor> <stable> <mods> def x(): T = x
 *
 *    <accessor> <mods> def x(): T = e
 *      -->  private[this] var x: T = e
 *           <accessor> <mods> def x(): T = x
 *
 *    <accessor> <mods> def x_=(y: T): Unit = ()
 *      --> <accessor> <mods> def x_=(y: T): Unit = x = y
 */
class Memoize extends MiniPhase with IdentityDenotTransformer { thisPhase =>
  import ast.tpd._

  override def phaseName: String = Memoize.name

  /* Makes sure that, after getters and constructors gen, there doesn't
   * exist non-deferred definitions that are not implemented. */
  override def checkPostCondition(tree: Tree)(using Context): Unit = {
    def errorLackImplementation(t: Tree) = {
      val firstPhaseId = t.symbol.initial.validFor.firstPhaseId
      val definingPhase = ctx.withPhase(firstPhaseId).phase.prev
      throw new AssertionError(
        i"Non-deferred definition introduced by $definingPhase lacks implementation: $t")
    }
    tree match {
      case ddef: DefDef
        if !ddef.symbol.is(Deferred) &&
           !ddef.symbol.isConstructor && // constructors bodies are added later at phase Constructors
           ddef.rhs == EmptyTree =>
        errorLackImplementation(ddef)
      case tdef: TypeDef
        if tdef.symbol.isClass && !tdef.symbol.is(Deferred) && tdef.rhs == EmptyTree =>
        errorLackImplementation(tdef)
      case _ =>
    }
    super.checkPostCondition(tree)
  }

  /** Should run after mixin so that fields get generated in the
   *  class that contains the concrete getter rather than the trait
   *  that defines it.
   */
  override def runsAfter: Set[String] = Set(Mixin.name)

  override def transformDefDef(tree: DefDef)(using Context): Tree = {
    val sym = tree.symbol

    def newField = {
      assert(!sym.hasAnnotation(defn.ScalaStaticAnnot))
      val fieldType =
        if (sym.isGetter) sym.info.resultType
        else /*sym.isSetter*/ sym.info.firstParamTypes.head

      ctx.newSymbol(
        owner = ctx.owner,
        name  = sym.name.asTermName.fieldName,
        flags = Private | (if (sym.is(StableRealizable)) EmptyFlags else Mutable),
        info  = fieldType,
        coord = tree.span
      ).withAnnotationsCarrying(sym, defn.FieldMetaAnnot)
       .enteredAfter(thisPhase)
    }

    def addAnnotations(denot: Denotation): Unit =
      denot match {
        case fieldDenot: SymDenotation if sym.annotations.nonEmpty =>
          val cpy = fieldDenot.copySymDenotation()
          cpy.annotations = sym.annotations
          cpy.installAfter(thisPhase)
        case _ => ()
      }

    def removeUnwantedAnnotations(denot: SymDenotation): Unit =
      if (sym.annotations.nonEmpty) {
        val cpy = sym.copySymDenotation()
        // Keep @deprecated annotation so that accessors can
        // be marked as deprecated in the bytecode.
        // TODO check the meta-annotations to know what to keep
        cpy.filterAnnotations(_.matches(defn.DeprecatedAnnot))
        cpy.installAfter(thisPhase)
      }

    val NoFieldNeeded = Lazy | Deferred | JavaDefined | (if (ctx.settings.YnoInline.value) EmptyFlags else Inline)

    def erasedBottomTree(sym: Symbol) =
      if (sym eq defn.NothingClass) Throw(nullLiteral)
      else if (sym eq defn.NullClass) nullLiteral
      else if (sym eq defn.BoxedUnitClass) ref(defn.BoxedUnit_UNIT)
      else {
        assert(false, s"$sym has no erased bottom tree")
        EmptyTree
      }

    if sym.is(Accessor, butNot = NoFieldNeeded) then
      def adaptToField(field: Symbol, tree: Tree): Tree =
        if (tree.isEmpty) tree else tree.ensureConforms(field.info.widen)

      def isErasableBottomField(field: Symbol, cls: Symbol): Boolean =
        !field.isVolatile && ((cls eq defn.NothingClass) || (cls eq defn.NullClass) || (cls eq defn.BoxedUnitClass))

      if sym.isGetter then
        val constantFinalVal = sym.isAllOf(Accessor | Final, butNot = Mutable) && tree.rhs.isInstanceOf[Literal]
        if constantFinalVal then
          // constant final vals do not need to be transformed at all, and do not need a field
          tree
        else
          val field = newField.asTerm
          var rhs = tree.rhs.changeOwnerAfter(sym, field, thisPhase)
          if (isWildcardArg(rhs)) rhs = EmptyTree
          val fieldDef = transformFollowing(ValDef(field, adaptToField(field, rhs)))
          val rhsClass = tree.tpt.tpe.widenDealias.classSymbol
          val getterRhs =
            if isErasableBottomField(field, rhsClass) then erasedBottomTree(rhsClass)
            else transformFollowingDeep(ref(field))(using ctx.withOwner(sym))
          val getterDef = cpy.DefDef(tree)(rhs = getterRhs)
          addAnnotations(fieldDef.denot)
          removeUnwantedAnnotations(sym)
          Thicket(fieldDef, getterDef)
      else if sym.isSetter then
        if (!sym.is(ParamAccessor)) { val Literal(Constant(())) = tree.rhs } // This is intended as an assertion
        val field = sym.field
        if !field.exists then
          // When transforming the getter, we determined that no field was needed.
          // In that case we can keep the setter as is, with a () rhs.
          tree
        else
          field.setFlag(Mutable) // Necessary for vals mixed in from traits
          val initializer =
            if (isErasableBottomField(field, tree.vparamss.head.head.tpt.tpe.classSymbol)) Literal(Constant(()))
            else Assign(ref(field), adaptToField(field, ref(tree.vparamss.head.head.symbol)))
          val setterDef = cpy.DefDef(tree)(rhs = transformFollowingDeep(initializer)(using ctx.withOwner(sym)))
          removeUnwantedAnnotations(sym)
          setterDef
      else
        // Curiously, some accessors from Scala2 have ' ' suffixes.
        // They count as neither getters nor setters.
        tree
    else
      tree
  }
}
