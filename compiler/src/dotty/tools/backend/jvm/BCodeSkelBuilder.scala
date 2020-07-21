package dotty.tools
package backend
package jvm

import scala.collection.{ mutable, immutable }
import scala.annotation.switch

import scala.tools.asm
import scala.tools.asm.util.{TraceMethodVisitor, ASMifier}
import java.io.PrintWriter

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.core.Annotations.Annotation
import dotty.tools.dotc.core.Decorators._
import dotty.tools.dotc.core.Flags._
import dotty.tools.dotc.core.StdNames.{nme, str}
import dotty.tools.dotc.core.Symbols._
import dotty.tools.dotc.core.Types.{MethodType, Type}
import dotty.tools.dotc.util.Spans._
import dotty.tools.dotc.report

/*
 *
 *  @author  Miguel Garcia, http://lamp.epfl.ch/~magarcia/ScalaCompilerCornerReloaded/
 *  @version 1.0
 *
 */
trait BCodeSkelBuilder extends BCodeHelpers {
  import int.{_, given _}
  import DottyBackendInterface.{symExtensions, _}
  import tpd._
  import bTypes._
  import coreBTypes._
  import bCodeAsmCommon._

  lazy val NativeAttr: Symbol = requiredClass[scala.native]

  /*
   * There's a dedicated PlainClassBuilder for each CompilationUnit,
   * which simplifies the initialization of per-class data structures in `genPlainClass()` which in turn delegates to `initJClass()`
   *
   * The entry-point to emitting bytecode instructions is `genDefDef()` where the per-method data structures are initialized,
   * including `resetMethodBookkeeping()` and `initJMethod()`.
   * Once that's been done, and assuming the method being visited isn't abstract, `emitNormalMethodBody()` populates
   * the ASM MethodNode instance with ASM AbstractInsnNodes.
   *
   * Given that CleanUp delivers trees that produce values on the stack,
   * the entry-point to all-things instruction-emit is `genLoad()`.
   * There, an operation taking N arguments results in recursively emitting instructions to lead each of them,
   * followed by emitting instructions to process those arguments (to be found at run-time on the operand-stack).
   *
   * In a few cases the above recipe deserves more details, as provided in the documentation for:
   *   - `genLoadTry()`
   *   - `genSynchronized()
   *   - `jumpDest` , `cleanups` , `labelDefsAtOrUnder`
   */
  abstract class PlainSkelBuilder(cunit: CompilationUnit)
    extends BCClassGen
    with    BCAnnotGen
    with    BCInnerClassGen
    with    JAndroidBuilder
    with    BCForwardersGen
    with    BCPickles
    with    BCJGenSigGen {

    // Strangely I can't find this in the asm code 255, but reserving 1 for "this"
    final val MaximumJvmParameters = 254

    // current class
    var cnode: ClassNode1          = null
    var thisName: String           = null // the internal name of the class being emitted

    var claszSymbol: Symbol        = null
    var isCZParcelable             = false
    var isCZStaticModule           = false

    /* ---------------- idiomatic way to ask questions to typer ---------------- */

    def paramTKs(app: Apply, take: Int = -1): List[BType] = app match {
      case Apply(fun, _) =>
      val funSym = fun.symbol
      (funSym.info.firstParamTypes map toTypeKind) // this tracks mentioned inner classes (in innerClassBufferASM)
    }

    def symInfoTK(sym: Symbol): BType = {
      toTypeKind(sym.info) // this tracks mentioned inner classes (in innerClassBufferASM)
    }

    def tpeTK(tree: Tree): BType = { toTypeKind(tree.tpe) }

    override def getCurrentCUnit(): CompilationUnit = { cunit }

    /* ---------------- helper utils for generating classes and fields ---------------- */

    def genPlainClass(cd: TypeDef) = cd match {
      case TypeDef(_, impl) =>
      assert(cnode == null, "GenBCode detected nested methods.")
      innerClassBufferASM.clear()

      claszSymbol       = cd.symbol
      isCZParcelable    = isAndroidParcelableClass(claszSymbol)
      isCZStaticModule  = claszSymbol.isStaticModuleClass
      thisName          = internalName(claszSymbol)

      cnode = new ClassNode1()

      initJClass(cnode)

      val methodSymbols = for (f <- cd.symbol.info.decls.toList if f.is(Method) && f.isTerm && !f.is(Module)) yield f
      val hasStaticCtor = methodSymbols exists (_.isStaticConstructor)
      if (!hasStaticCtor) {
        // but needs one ...
        if (isCZStaticModule || isCZParcelable) {
          fabricateStaticInit()
        }
      }

      val optSerial: Option[Long] =
        claszSymbol.getAnnotation(defn.SerialVersionUIDAnnot).flatMap { annot =>
          if (claszSymbol.is(Trait)) {
            report.error("@SerialVersionUID does nothing on a trait", annot.tree.sourcePos)
            None
          } else {
            val vuid = annot.argumentConstant(0).map(_.longValue)
            if (vuid.isEmpty)
              report.error("The argument passed to @SerialVersionUID must be a constant",
                annot.argument(0).getOrElse(annot.tree).sourcePos)
            vuid
          }
        }
      if (optSerial.isDefined) { addSerialVUID(optSerial.get, cnode)}

      addClassFields()

      innerClassBufferASM ++= classBTypeFromSymbol(claszSymbol).info.memberClasses
      gen(impl)
      addInnerClassesASM(cnode, innerClassBufferASM.toList)

      if (AsmUtils.traceClassEnabled && cnode.name.contains(AsmUtils.traceClassPattern))
        AsmUtils.traceClass(cnode)

      cnode.innerClasses
      assert(cd.symbol == claszSymbol, "Someone messed up BCodePhase.claszSymbol during genPlainClass().")

    } // end of method genPlainClass()

    /*
     * must-single-thread
     */
    private def initJClass(jclass: asm.ClassVisitor): Unit = {

      val ps = claszSymbol.info.parents
      val superClass: String = if (ps.isEmpty) ObjectReference.internalName else internalName(ps.head.widenDealias.typeSymbol)
      val interfaceNames = classBTypeFromSymbol(claszSymbol).info.interfaces map {
        case classBType =>
          if (classBType.isNestedClass) { innerClassBufferASM += classBType }
          classBType.internalName
      }

      val flags = javaFlags(claszSymbol)

      val thisSignature = getGenericSignature(claszSymbol, claszSymbol.owner)
      cnode.visit(classfileVersion, flags,
                  thisName, thisSignature,
                  superClass, interfaceNames.toArray)

      if (emitSource) {
        cnode.visitSource(cunit.source.file.name, null /* SourceDebugExtension */)
      }

      enclosingMethodAttribute(claszSymbol, internalName, asmMethodType(_).descriptor) match {
        case Some(EnclosingMethodEntry(className, methodName, methodDescriptor)) =>
          cnode.visitOuterClass(className, methodName, methodDescriptor)
        case _ => ()
      }

      val ssa = None // TODO: inlined form `getAnnotPickle(thisName, claszSymbol)`. Should something be done on Dotty?
      cnode.visitAttribute(if (ssa.isDefined) pickleMarkerLocal else pickleMarkerForeign)
      emitAnnotations(cnode, claszSymbol.annotations ++ ssa)

      if (isCZStaticModule || isCZParcelable) {

        if (isCZStaticModule) { addModuleInstanceField() }

      } else {

        val skipStaticForwarders = (claszSymbol.isInterface || claszSymbol.is(Module) || ctx.settings.XnoForwarders.value)
        if (!skipStaticForwarders) {
          val lmoc = claszSymbol.companionModule
          // add static forwarders if there are no name conflicts; see bugs #363 and #1735
          if (lmoc != NoSymbol) {
            // it must be a top level class (name contains no $s)
            val isCandidateForForwarders =  (lmoc.is(Module)) && lmoc.isStatic
            if (isCandidateForForwarders) {
              report.log(s"Adding static forwarders from '$claszSymbol' to implementations in '$lmoc'")
              addForwarders(cnode, thisName, lmoc.moduleClass)
            }
          }
        }

      }

      // the invoker is responsible for adding a class-static constructor.

    } // end of method initJClass

    /*
     * can-multi-thread
     */
    private def addModuleInstanceField(): Unit = {
      val fv =
        cnode.visitField(GenBCodeOps.PublicStaticFinal, // TODO confirm whether we really don't want ACC_SYNTHETIC nor ACC_DEPRECATED
                         str.MODULE_INSTANCE_FIELD,
                         "L" + thisName + ";",
                         null, // no java-generic-signature
                         null  // no initial value
        )

      fv.visitEnd()
    }

    /*
     * must-single-thread
     */
    private def fabricateStaticInit(): Unit = {

      val clinit: asm.MethodVisitor = cnode.visitMethod(
        GenBCodeOps.PublicStatic, // TODO confirm whether we really don't want ACC_SYNTHETIC nor ACC_DEPRECATED
        CLASS_CONSTRUCTOR_NAME,
        "()V",
        null, // no java-generic-signature
        null  // no throwable exceptions
      )
      clinit.visitCode()

      /* "legacy static initialization" */
      if (isCZStaticModule) {
        clinit.visitTypeInsn(asm.Opcodes.NEW, thisName)
        clinit.visitMethodInsn(asm.Opcodes.INVOKESPECIAL,
                               thisName, INSTANCE_CONSTRUCTOR_NAME, "()V", false)
      }
      if (isCZParcelable) { legacyAddCreatorCode(clinit, cnode, thisName) }
      clinit.visitInsn(asm.Opcodes.RETURN)

      clinit.visitMaxs(0, 0) // just to follow protocol, dummy arguments
      clinit.visitEnd()
    }

    def addClassFields(): Unit = {
      /*  Non-method term members are fields, except for module members. Module
       *  members can only happen on .NET (no flatten) for inner traits. There,
       *  a module symbol is generated (transformInfo in mixin) which is used
       *  as owner for the members of the implementation class (so that the
       *  backend emits them as static).
       *  No code is needed for this module symbol.
       */
      for (f <- claszSymbol.info.decls.filter(p => p.isTerm && !p.is(Method))) {
        val javagensig = getGenericSignature(f, claszSymbol)
        val flags = javaFieldFlags(f)

        assert(!f.isStaticMember || !claszSymbol.isInterface || !f.is(Mutable),
          s"interface $claszSymbol cannot have non-final static field $f")

        val jfield = new asm.tree.FieldNode(
          flags,
          f.javaSimpleName,
          symInfoTK(f).descriptor,
          javagensig,
          null // no initial value
        )
        cnode.fields.add(jfield)
        emitAnnotations(jfield, f.annotations)
      }

    } // end of method addClassFields()

    // current method
    var mnode: MethodNode1         = null
    var jMethodName: String        = null
    var isMethSymStaticCtor        = false
    var returnType: BType          = null
    var methSymbol: Symbol         = null
    // in GenASM this is local to genCode(), ie should get false whenever a new method is emitted (including fabricated ones eg addStaticInit())
    var isModuleInitialized        = false
    // used by genLoadTry() and genSynchronized()
    var earlyReturnVar: Symbol     = null
    var shouldEmitCleanup          = false
    // line numbers
    var lastEmittedLineNr          = -1

    object bc extends JCodeMethodN {
      override def jmethod = PlainSkelBuilder.this.mnode
    }

    /* ---------------- Part 1 of program points, ie Labels in the ASM world ---------------- */

    /*
     *  A jump is represented as an Apply node whose symbol denotes a LabelDef, the target of the jump.
     *  The `jumpDest` map is used to:
     *    (a) find the asm.Label for the target, given an Apply node's symbol;
     *    (b) anchor an asm.Label in the instruction stream, given a LabelDef node.
     *  In other words, (a) is necessary when visiting a jump-source, and (b) when visiting a jump-target.
     *  A related map is `labelDef`: it has the same keys as `jumpDest` but its values are LabelDef nodes not asm.Labels.
     *
     */
    var jumpDest: immutable.Map[ /* Labeled or LabelDef */ Symbol, asm.Label ] = null
    def programPoint(labelSym: Symbol): asm.Label = {
      assert(labelSym.is(Label), s"trying to map a non-label symbol to an asm.Label, at: ${labelSym.span}")
      jumpDest.getOrElse(labelSym, {
        val pp = new asm.Label
        jumpDest += (labelSym -> pp)
        pp
      })
    }

    /*
     *  A program point may be lexically nested (at some depth)
     *    (a) in the try-clause of a try-with-finally expression
     *    (b) in a synchronized block.
     *  Each of the constructs above establishes a "cleanup block" to execute upon
     *  both normal-exit, early-return, and abrupt-termination of the instructions it encloses.
     *
     *  The `cleanups` LIFO queue represents the nesting of active (for the current program point)
     *  pending cleanups. For each such cleanup an asm.Label indicates the start of its cleanup-block.
     *  At any given time during traversal of the method body,
     *  the head of `cleanups` denotes the cleanup-block for the closest enclosing try-with-finally or synchronized-expression.
     *
     *  `cleanups` is used:
     *
     *    (1) upon visiting a Return statement.
     *        In case of pending cleanups, we can't just emit a RETURN instruction, but must instead:
     *          - store the result (if any) in `earlyReturnVar`, and
     *          - jump to the next pending cleanup.
     *        See `genReturn()`
     *
     *    (2) upon emitting a try-with-finally or a synchronized-expr,
     *        In these cases, the targets of the above jumps are emitted,
     *        provided an early exit was actually encountered somewhere in the protected clauses.
     *        See `genLoadTry()` and `genSynchronized()`
     *
     *  The code thus emitted for jumps and targets covers the early-return case.
     *  The case of abrupt (ie exceptional) termination is covered by exception handlers
     *  emitted for that purpose as described in `genLoadTry()` and `genSynchronized()`.
     */
    var cleanups: List[asm.Label] = Nil
    def registerCleanup(finCleanup: asm.Label): Unit = {
      if (finCleanup != null) { cleanups = finCleanup :: cleanups }
    }
    def unregisterCleanup(finCleanup: asm.Label): Unit = {
      if (finCleanup != null) {
        assert(cleanups.head eq finCleanup,
               s"Bad nesting of cleanup operations: $cleanups trying to unregister: $finCleanup")
        cleanups = cleanups.tail
      }
    }

    /* ---------------- local variables and params ---------------- */

    case class Local(tk: BType, name: String, idx: Int, isSynth: Boolean)

    /*
     * Bookkeeping for method-local vars and method-params.
     *
     * TODO: use fewer slots. local variable slots are never re-used in separate blocks.
     * In the following example, x and y could use the same slot.
     *   def foo() = {
     *     { val x = 1 }
     *     { val y = "a" }
     *   }
     */
    object locals {

      private val slots = mutable.AnyRefMap.empty[Symbol, Local] // (local-or-param-sym -> Local(BType, name, idx, isSynth))

      private var nxtIdx = -1 // next available index for local-var

      def reset(isStaticMethod: Boolean): Unit = {
        slots.clear()
        nxtIdx = if (isStaticMethod) 0 else 1
      }

      def contains(locSym: Symbol): Boolean = { slots.contains(locSym) }

      def apply(locSym: Symbol): Local = { slots.apply(locSym) }

      /* Make a fresh local variable, ensuring a unique name.
       * The invoker must make sure inner classes are tracked for the sym's tpe.
       */
      def makeLocal(tk: BType, name: String, tpe: Type, pos: Span): Symbol = {

        val locSym = newSymbol(methSymbol, name.toTermName, Synthetic, tpe, NoSymbol, pos)
        makeLocal(locSym, tk)
        locSym
      }

      def makeLocal(locSym: Symbol): Local = {
        makeLocal(locSym, symInfoTK(locSym))
      }

      def getOrMakeLocal(locSym: Symbol): Local = {
        // `getOrElse` below has the same effect as `getOrElseUpdate` because `makeLocal()` adds an entry to the `locals` map.
        slots.getOrElse(locSym, makeLocal(locSym))
      }

      private def makeLocal(sym: Symbol, tk: BType): Local = {
        assert(nxtIdx != -1, "not a valid start index")
        val loc = Local(tk, sym.javaSimpleName, nxtIdx, sym.is(Synthetic))
        val existing = slots.put(sym, loc)
        if (existing.isDefined)
          report.error("attempt to create duplicate local var.", ctx.source.atSpan(sym.span))
        assert(tk.size > 0, "makeLocal called for a symbol whose type is Unit.")
        nxtIdx += tk.size
        loc
      }

      // not to be confused with `fieldStore` and `fieldLoad` which also take a symbol but a field-symbol.
      def store(locSym: Symbol): Unit = {
        val Local(tk, _, idx, _) = slots(locSym)
        bc.store(idx, tk)
      }

      def load(locSym: Symbol): Unit = {
        val Local(tk, _, idx, _) = slots(locSym)
        bc.load(idx, tk)
      }

    }

    /* ---------------- Part 2 of program points, ie Labels in the ASM world ---------------- */

    // bookkeeping the scopes of non-synthetic local vars, to emit debug info (`emitVars`).
    var varsInScope: List[(Symbol, asm.Label)] = null // (local-var-sym -> start-of-scope)

    // helpers around program-points.
    def lastInsn: asm.tree.AbstractInsnNode = mnode.instructions.getLast
    def currProgramPoint(): asm.Label = {
      lastInsn match {
        case labnode: asm.tree.LabelNode => labnode.getLabel
        case _ =>
          val pp = new asm.Label
          mnode visitLabel pp
          pp
      }
    }
    def markProgramPoint(lbl: asm.Label): Unit = {
      val skip = (lbl == null) || isAtProgramPoint(lbl)
      if (!skip) { mnode visitLabel lbl }
    }
    def isAtProgramPoint(lbl: asm.Label): Boolean = {
      def getNonLineNumberNode(a: asm.tree.AbstractInsnNode): asm.tree.AbstractInsnNode  = a match {
        case a: asm.tree.LineNumberNode => getNonLineNumberNode(a.getPrevious) // line numbers aren't part of code itself
        case _ => a
      }
      (getNonLineNumberNode(lastInsn) match {
        case labnode: asm.tree.LabelNode => (labnode.getLabel == lbl);
        case _ => false } )
    }
    def lineNumber(tree: Tree): Unit = {
      if (!emitLines || !tree.span.exists) return;
      val nr = ctx.source.atSpan(tree.span).line + 1
      if (nr != lastEmittedLineNr) {
        lastEmittedLineNr = nr
        lastInsn match {
          case lnn: asm.tree.LineNumberNode =>
            // overwrite previous landmark as no instructions have been emitted for it
            lnn.line = nr
          case _ =>
            mnode.visitLineNumber(nr, currProgramPoint())
        }
      }
    }

    // on entering a method
    def resetMethodBookkeeping(dd: DefDef) = {
      val rhs = dd.rhs
      locals.reset(isStaticMethod = methSymbol.isStaticMember)
      jumpDest = immutable.Map.empty[ /* LabelDef */ Symbol, asm.Label ]

      // check previous invocation of genDefDef exited as many varsInScope as it entered.
      assert(varsInScope == null, "Unbalanced entering/exiting of GenBCode's genBlock().")
      // check previous invocation of genDefDef unregistered as many cleanups as it registered.
      assert(cleanups == Nil, "Previous invocation of genDefDef didn't unregister as many cleanups as it registered.")
      isModuleInitialized = false
      earlyReturnVar      = null
      shouldEmitCleanup   = false

      lastEmittedLineNr = -1
    }

    /* ---------------- top-down traversal invoking ASM Tree API along the way ---------------- */

    def gen(tree: Tree): Unit = {
      tree match {
        case tpd.EmptyTree => ()

        case ValDef(name, tpt, rhs) => () // fields are added in `genPlainClass()`, via `addClassFields()`

        case dd: DefDef =>
          /* First generate a static forwarder if this is a non-private trait
           * trait method. This is required for super calls to this method, which
           * go through the static forwarder in order to work around limitations
           * of the JVM.
           * In theory, this would go in a separate MiniPhase, but it would have to
           * sit in a MegaPhase of its own between GenSJSIR and GenBCode, so the cost
           * is not worth it. We directly do it in this back-end instead, which also
           * kind of makes sense because it is JVM-specific.
           */
          val sym = dd.symbol
          val needsStaticImplMethod =
            claszSymbol.isInterface && !dd.rhs.isEmpty && !sym.isPrivate && !sym.isStaticMember
          if needsStaticImplMethod then
            genStaticForwarderForDefDef(dd)

          genDefDef(dd)

        case tree: Template =>
          val body =
            if (tree.constr.rhs.isEmpty) tree.body
            else tree.constr :: tree.body
          body foreach gen

        case _ => abort(s"Illegal tree in gen: $tree")
      }
    }

    /*
     * must-single-thread
     */
    def initJMethod(flags: Int, paramAnnotations: List[List[Annotation]]): Unit = {

      val jgensig = getGenericSignature(methSymbol, claszSymbol)
      val (excs, others) = methSymbol.annotations.partition(_.symbol eq defn.ThrowsAnnot)
      val thrownExceptions: List[String] = getExceptions(excs)

      val bytecodeName =
        if (isMethSymStaticCtor) CLASS_CONSTRUCTOR_NAME
        else jMethodName

      val mdesc = asmMethodType(methSymbol).descriptor
      mnode = cnode.visitMethod(
        flags,
        bytecodeName,
        mdesc,
        jgensig,
        mkArrayS(thrownExceptions)
      ).asInstanceOf[MethodNode1]

      // TODO param names: (m.params map (p => javaName(p.sym)))

      emitAnnotations(mnode, others)
      emitParamAnnotations(mnode, paramAnnotations)

    } // end of method initJMethod

    private def genStaticForwarderForDefDef(dd: DefDef): Unit =
      val forwarderDef = makeStaticForwarder(dd)
      genDefDef(forwarderDef)

    /* Generates a synthetic static forwarder for a trait method.
     * For a method such as
     *   def foo(...args: Ts): R
     * in trait X, we generate the following method:
     *   static def foo$($this: X, ...args: Ts): R =
     *     invokespecial $this.X::foo(...args)
     * We force an invokespecial with the attachment UseInvokeSpecial. It is
     * necessary to make sure that the call will not follow overrides of foo()
     * in subtraits and subclasses, since the whole point of this forward is to
     * encode super calls.
     */
    private def makeStaticForwarder(dd: DefDef): DefDef =
      val origSym = dd.symbol.asTerm
      val name = traitSuperAccessorName(origSym)
      val info = origSym.info match
        case mt: MethodType =>
          MethodType(nme.SELF :: mt.paramNames, origSym.owner.typeRef :: mt.paramInfos, mt.resType)
      val sym = origSym.copy(
        name = name.toTermName,
        flags = Method | JavaStatic,
        info = info
      )
      tpd.DefDef(sym.asTerm, { paramss =>
        val params = paramss.head
        tpd.Apply(params.head.select(origSym), params.tail)
          .withAttachment(BCodeHelpers.UseInvokeSpecial, ())
      })

    def genDefDef(dd: DefDef): Unit = {
      val rhs = dd.rhs
      val vparamss = dd.vparamss
      // the only method whose implementation is not emitted: getClass()
      if (dd.symbol eq defn.Any_getClass) { return }
      assert(mnode == null, "GenBCode detected nested method.")

      methSymbol  = dd.symbol
      jMethodName = methSymbol.javaSimpleName
      returnType  = asmMethodType(dd.symbol).returnType
      isMethSymStaticCtor = methSymbol.isStaticConstructor

      resetMethodBookkeeping(dd)

      // add method-local vars for params

      assert(vparamss.isEmpty || vparamss.tail.isEmpty, s"Malformed parameter list: $vparamss")
      val params = if (vparamss.isEmpty) Nil else vparamss.head
      for (p <- params) { locals.makeLocal(p.symbol) }
      // debug assert((params.map(p => locals(p.symbol).tk)) == asmMethodType(methSymbol).getArgumentTypes.toList, "debug")

      if (params.size > MaximumJvmParameters) {
        // SI-7324
        report.error(s"Platform restriction: a parameter list's length cannot exceed $MaximumJvmParameters.", ctx.source.atSpan(methSymbol.span))
        return
      }

      val isNative         = methSymbol.hasAnnotation(NativeAttr)
      val isAbstractMethod = (methSymbol.is(Deferred) || (methSymbol.owner.isInterface && ((methSymbol.is(Deferred))  || methSymbol.isClassConstructor)))
      val flags = GenBCodeOps.mkFlags(
        javaFlags(methSymbol),
        if (isAbstractMethod)        asm.Opcodes.ACC_ABSTRACT   else 0,
        if (false /*methSymbol.isStrictFP*/)   asm.Opcodes.ACC_STRICT     else 0,
        if (isNative)                asm.Opcodes.ACC_NATIVE     else 0  // native methods of objects are generated in mirror classes
      )

      // TODO needed? for(ann <- m.symbol.annotations) { ann.symbol.initialize }
      initJMethod(flags, params.map(p => p.symbol.annotations))


      if (!isAbstractMethod && !isNative) {

        def emitNormalMethodBody(): Unit = {
          val veryFirstProgramPoint = currProgramPoint()
          genLoad(rhs, returnType)

          rhs match {
            case (_: Return) | Block(_, (_: Return)) => ()
            case (_: Apply) | Block(_, (_: Apply)) if rhs.symbol eq defn.throwMethod => ()
            case tpd.EmptyTree =>
              report.error("Concrete method has no definition: " + dd + (
                if (ctx.settings.Ydebug.value) "(found: " + methSymbol.owner.info.decls.toList.mkString(", ") + ")"
                else ""),
                ctx.source.atSpan(NoSpan)
              )
            case _ =>
              bc emitRETURN returnType
          }
          if (emitVars) {
            // add entries to LocalVariableTable JVM attribute
            val onePastLastProgramPoint = currProgramPoint()
            val hasStaticBitSet = ((flags & asm.Opcodes.ACC_STATIC) != 0)
            if (!hasStaticBitSet) {
              mnode.visitLocalVariable(
                "this",
                "L" + thisName + ";",
                null,
                veryFirstProgramPoint,
                onePastLastProgramPoint,
                0
              )
            }
            for (p <- params) { emitLocalVarScope(p.symbol, veryFirstProgramPoint, onePastLastProgramPoint, force = true) }
          }

          if (isMethSymStaticCtor) { appendToStaticCtor(dd) }
        } // end of emitNormalMethodBody()

        lineNumber(rhs)
        emitNormalMethodBody()

        // Note we don't invoke visitMax, thus there are no FrameNode among mnode.instructions.
        // The only non-instruction nodes to be found are LabelNode and LineNumberNode.
      }

      if (AsmUtils.traceMethodEnabled && mnode.name.contains(AsmUtils.traceMethodPattern))
        AsmUtils.traceMethod(mnode)

      mnode = null
    } // end of method genDefDef()

    /*
     *  must-single-thread
     *
     *  TODO document, explain interplay with `fabricateStaticInit()`
     */
    private def appendToStaticCtor(dd: DefDef): Unit = {

      def insertBefore(
            location: asm.tree.AbstractInsnNode,
            i0: asm.tree.AbstractInsnNode,
            i1: asm.tree.AbstractInsnNode): Unit = {
        if (i0 != null) {
          mnode.instructions.insertBefore(location, i0.clone(null))
          mnode.instructions.insertBefore(location, i1.clone(null))
        }
      }

      // collect all return instructions
      var rets: List[asm.tree.AbstractInsnNode] = Nil
      mnode foreachInsn { i => if (i.getOpcode() == asm.Opcodes.RETURN) { rets ::= i  } }
      if (rets.isEmpty) { return }

      var insnModA: asm.tree.AbstractInsnNode = null
      var insnModB: asm.tree.AbstractInsnNode = null
      // call object's private ctor from static ctor
      if (isCZStaticModule) {
        // NEW `moduleName`
        val className = internalName(methSymbol.enclosingClass)
        insnModA      = new asm.tree.TypeInsnNode(asm.Opcodes.NEW, className)
        // INVOKESPECIAL <init>
        val callee = methSymbol.enclosingClass.primaryConstructor
        val jname  = callee.javaSimpleName
        val jowner = internalName(callee.owner)
        val jtype  = asmMethodType(callee).descriptor
        insnModB   = new asm.tree.MethodInsnNode(asm.Opcodes.INVOKESPECIAL, jowner, jname, jtype, false)
      }

      var insnParcA: asm.tree.AbstractInsnNode = null
      var insnParcB: asm.tree.AbstractInsnNode = null
      // android creator code
      if (isCZParcelable) {
        // add a static field ("CREATOR") to this class to cache android.os.Parcelable$Creator
        val andrFieldDescr = getClassBTypeAndRegisterInnerClass(AndroidCreatorClass).descriptor
        cnode.visitField(
          asm.Opcodes.ACC_STATIC | asm.Opcodes.ACC_FINAL,
          "CREATOR",
          andrFieldDescr,
          null,
          null
        )
        // INVOKESTATIC CREATOR(): android.os.Parcelable$Creator; -- TODO where does this Android method come from?
        val callee = claszSymbol.companionModule.info.member(androidFieldName).symbol
        val jowner = internalName(callee.owner)
        val jname  = callee.javaSimpleName
        val jtype  = asmMethodType(callee).descriptor
        insnParcA  = new asm.tree.MethodInsnNode(asm.Opcodes.INVOKESTATIC, jowner, jname, jtype, false)
        // PUTSTATIC `thisName`.CREATOR;
        insnParcB  = new asm.tree.FieldInsnNode(asm.Opcodes.PUTSTATIC, thisName, "CREATOR", andrFieldDescr)
      }

      // insert a few instructions for initialization before each return instruction
      for(r <- rets) {
        insertBefore(r, insnModA,  insnModB)
        insertBefore(r, insnParcA, insnParcB)
      }

    }

    def emitLocalVarScope(sym: Symbol, start: asm.Label, end: asm.Label, force: Boolean = false): Unit = {
      val Local(tk, name, idx, isSynth) = locals(sym)
      if (force || !isSynth) {
        mnode.visitLocalVariable(name, tk.descriptor, null, start, end, idx)
      }
    }

    def genLoad(tree: Tree, expectedType: BType): Unit

  } // end of class PlainSkelBuilder

}
