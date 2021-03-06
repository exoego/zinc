/*
 * Zinc - The incremental compiler for Scala.
 * Copyright Lightbend, Inc. and Mark Harrah
 *
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package sbt
package internal
package inc

import sbt.util.Logger
import xsbti.{ FileConverter, VirtualFile, VirtualFileRef }
import xsbt.api.APIUtil
import xsbti.api.AnalyzedClass
import xsbti.compile.{
  Changes,
  DependencyChanges,
  IncOptions,
  Output,
  ClassFileManager => XClassFileManager
}
import xsbti.compile.analysis.{ ReadStamps, Stamp => XStamp }
import scala.collection.Iterator
import Incremental.{ PrefixingLogger, apiDebug }

/**
 * Defines the core logic to compile incrementally and apply the class invalidation after
 * every compiler run. This class defines only the core logic and the algorithm-specific
 * bits are implemented in its subclasses.
 *
 * In the past, there were several incremental compiler strategies. Now, there's only
 * one, the default [[IncrementalNameHashing]] strategy that invalidates classes based
 * on name hashes.
 *
 * @param log An instance of a logger.
 * @param options An instance of incremental compiler options.
 */
private[inc] abstract class IncrementalCommon(
    val log: Logger,
    options: IncOptions,
    profiler: RunProfiler
) extends InvalidationProfilerUtils {
  // Work around bugs in classpath handling such as the "currently" problematic -javabootclasspath
  private[this] def enableShallowLookup: Boolean =
    java.lang.Boolean.getBoolean("xsbt.skip.cp.lookup")

  private[this] final val wrappedLog = new PrefixingLogger("[inv] ")(log)
  def debug(s: => String): Unit = if (options.relationsDebug) wrappedLog.debug(s) else ()

  final def iterations(state0: CycleState): Iterator[CycleState] =
    new Iterator[CycleState] {
      var state: CycleState = state0
      def hasNext: Boolean = state.hasNext
      def next: CycleState = {
        val n = state.next
        state = n
        n
      }
    }
  case class CycleState(
      invalidatedClasses: Set[String],
      initialChangedSources: Set[VirtualFileRef],
      allSources: Set[VirtualFile],
      converter: FileConverter,
      binaryChanges: DependencyChanges,
      lookup: ExternalLookup,
      previous: Analysis,
      doCompile: (Set[VirtualFile], DependencyChanges) => Analysis,
      classfileManager: XClassFileManager,
      output: Output,
      cycleNum: Int
  ) {
    def hasNext: Boolean = invalidatedClasses.nonEmpty || initialChangedSources.nonEmpty
    def next: CycleState = {
      // Compute all the invalidated classes by aggregating invalidated package objects
      val invalidatedByPackageObjects =
        invalidatedPackageObjects(invalidatedClasses, previous.relations, previous.apis)
      val classesToRecompile = invalidatedClasses ++ invalidatedByPackageObjects
      val vs = allSources map { x =>
        (x: VirtualFileRef)
      }
      // Computes which source files are mapped to the invalidated classes and recompile them
      val invalidatedRefs: Set[VirtualFileRef] =
        mapInvalidationsToSources(classesToRecompile, initialChangedSources, vs, previous)
      val invalidatedSources: Set[VirtualFile] = invalidatedRefs map { converter.toVirtualFile }
      val current =
        recompileClasses(
          invalidatedSources,
          converter,
          binaryChanges,
          previous,
          doCompile,
          classfileManager
        )

      // Return immediate analysis as all sources have been recompiled
      if (invalidatedSources == allSources)
        CycleState(
          Set.empty,
          Set.empty,
          allSources,
          converter,
          IncrementalCommon.emptyChanges,
          lookup,
          current,
          doCompile,
          classfileManager,
          output,
          cycleNum + 1
        )
      else {
        val recompiledClasses: Set[String] = {
          // Represents classes detected as changed externally and internally (by a previous cycle)
          classesToRecompile ++
            // Maps the changed sources by the user to class names we can count as invalidated
            initialChangedSources.flatMap(previous.relations.classNames) ++
            initialChangedSources.flatMap(current.relations.classNames)
        }

        val newApiChanges =
          detectAPIChanges(recompiledClasses, previous.apis.internalAPI, current.apis.internalAPI)
        debug("\nChanges:\n" + newApiChanges)
        val nextInvalidations = invalidateAfterInternalCompilation(
          current.relations,
          newApiChanges,
          recompiledClasses,
          cycleNum >= options.transitiveStep,
          IncrementalCommon.comesFromScalaSource(previous.relations, Some(current.relations))
        )

        val continue = lookup.shouldDoIncrementalCompilation(nextInvalidations, current)

        profiler.registerCycle(
          invalidatedClasses,
          invalidatedByPackageObjects,
          initialChangedSources,
          invalidatedSources,
          recompiledClasses,
          newApiChanges,
          nextInvalidations,
          continue
        )
        CycleState(
          if (continue) nextInvalidations else Set.empty,
          Set.empty,
          allSources,
          converter,
          IncrementalCommon.emptyChanges,
          lookup,
          current,
          doCompile,
          classfileManager,
          output,
          cycleNum + 1
        )
      }
    }
  }

  /**
   * Compile a project as many times as it is required incrementally. This logic is the start
   * point of the incremental compiler and the place where all the invalidation logic happens.
   *
   * The current logic does merge the compilation step and the analysis step, by making them
   * execute sequentially. There are cases where, for performance reasons, build tools and
   * users of Zinc may be interested in separating the two. If this is the case, the user needs
   * to reimplement this logic by copy pasting this logic and relying on the utils defined
   * in `IncrementalCommon`.
   *
   * @param invalidatedClasses The invalidated classes either initially or by a previous cycle.
   * @param initialChangedSources The initial changed sources by the user, empty if previous cycle.
   * @param allSources All the sources defined in the project and compiled in the first iteration.
   * @param converter FileConverter to convert between Path and VirtualFileRef.
   * @param binaryChanges The initially detected changes derived from [[InitialChanges]].
   * @param lookup The lookup instance to query classpath and analysis information.
   * @param previous The last analysis file known of this project.
   * @param doCompile A function that compiles a project and returns an analysis file.
   * @param classfileManager The manager that takes care of class files in compilation.
   * @param cycleNum The counter of incremental compiler cycles.
   * @return A fresh analysis file after all the incremental compiles have been run.
   */
  final def cycle(
      invalidatedClasses: Set[String],
      initialChangedSources: Set[VirtualFileRef],
      allSources: Set[VirtualFile],
      converter: FileConverter,
      binaryChanges: DependencyChanges,
      lookup: ExternalLookup,
      previous: Analysis,
      doCompile: (Set[VirtualFile], DependencyChanges) => Analysis,
      classfileManager: XClassFileManager,
      output: Output,
      cycleNum: Int
  ): Analysis = {
    var s = CycleState(
      invalidatedClasses,
      initialChangedSources,
      allSources,
      converter,
      binaryChanges,
      lookup,
      previous,
      doCompile,
      classfileManager,
      output,
      cycleNum
    )
    val it = iterations(s)
    while (it.hasNext) {
      s = it.next
    }
    s.previous
  }

  def mapInvalidationsToSources(
      invalidatedClasses: Set[String],
      aggregateSources: Set[VirtualFileRef],
      allSources: Set[VirtualFileRef],
      previous: Analysis
  ): Set[VirtualFileRef] = {
    def expand(invalidated: Set[VirtualFileRef]): Set[VirtualFileRef] = {
      val recompileAllFraction = options.recompileAllFraction
      if (invalidated.size <= allSources.size * recompileAllFraction) invalidated
      else {
        log.debug(
          s"Recompiling all sources: number of invalidated sources > ${recompileAllFraction * 100.00}% of all sources"
        )
        allSources ++ invalidated // Union because `all` doesn't contain removed sources
      }
    }

    expand(invalidatedClasses.flatMap(previous.relations.definesClass) ++ aggregateSources)
  }

  def recompileClasses(
      sources: Set[VirtualFile],
      converter: FileConverter,
      binaryChanges: DependencyChanges,
      previous: Analysis,
      doCompile: (Set[VirtualFile], DependencyChanges) => Analysis,
      classfileManager: XClassFileManager
  ): Analysis = {
    val pruned =
      IncrementalCommon.pruneClassFilesOfInvalidations(
        sources,
        previous,
        classfileManager,
        converter
      )
    debug("********* Pruned: \n" + pruned.relations + "\n*********")
    val fresh = doCompile(sources, binaryChanges)
    debug("********* Fresh: \n" + fresh.relations + "\n*********")

    val products = fresh.relations.allProducts.toList
    /* This is required for both scala compilation and forked java compilation, despite
     *  being redundant for the most common Java compilation (using the local compiler). */
    classfileManager.generated(products.map(converter.toVirtualFile(_)).toArray)

    val merged = pruned ++ fresh
    debug("********* Merged: \n" + merged.relations + "\n*********")
    merged
  }

  /**
   * Detects the API changes of `recompiledClasses`.
   *
   * @param recompiledClasses The list of classes that were recompiled in this round.
   * @param oldAPI A function that returns the previous class associated with a given class name.
   * @param newAPI A function that returns the current class associated with a given class name.
   * @return A list of API changes of the given two analyzed classes.
   */
  def detectAPIChanges(
      recompiledClasses: collection.Set[String],
      oldAPI: String => AnalyzedClass,
      newAPI: String => AnalyzedClass
  ): APIChanges = {
    def classDiff(className: String, a: AnalyzedClass, b: AnalyzedClass): Option[APIChange] = {
      if (a.compilationTimestamp() == b.compilationTimestamp() && (a.apiHash == b.apiHash)) None
      else {
        val hasMacro = a.hasMacro || b.hasMacro
        if (hasMacro && IncOptions.getRecompileOnMacroDef(options)) {
          Some(APIChangeDueToMacroDefinition(className))
        } else findAPIChange(className, a, b)
      }
    }

    val apiChanges = recompiledClasses.flatMap(name => classDiff(name, oldAPI(name), newAPI(name)))
    if (apiDebug(options) && apiChanges.nonEmpty) {
      logApiChanges(apiChanges, oldAPI, newAPI)
    }
    new APIChanges(apiChanges)
  }

  /**
   * Detects the initial changes after the first compiler iteration is over.
   *
   * This method only requires the compiled sources, the previous analysis and the
   * stamps reader to be able to populate [[InitialChanges]] with all the data
   * structures that will be used for the first incremental compiler cycle.
   *
   * The logic of this method takes care of the following tasks:
   *
   * 1. Detecting the sources that changed between the past and present compiler iteration.
   * 2. Detecting the removed products based on the stamps from the previous and current products.
   * 3. Detects the class names changed in a library (classpath entry such as jars or analysis).
   * 4. Computes the API changes in dependent and external projects.
   *
   * @param sources The sources that were compiled.
   * @param previousAnalysis The analysis from the previous compilation.
   * @param stamps The stamps reader to get stamp for sources, products and binaries.
   * @param lookup The lookup instance that provides hooks and inspects the classpath.
   * @param equivS A function to compare stamps.
   * @return An instance of [[InitialChanges]].
   */
  def detectInitialChanges(
      sources: Set[VirtualFile],
      previousAnalysis: Analysis,
      stamps: ReadStamps,
      lookup: Lookup,
      converter: FileConverter,
      output: Output
  )(implicit equivS: Equiv[XStamp]): InitialChanges = {
    import IncrementalCommon.isLibraryModified
    import lookup.lookupAnalyzedClass
    val previous = previousAnalysis.stamps
    val previousRelations = previousAnalysis.relations

    val sourceChanges: Changes[VirtualFileRef] = lookup.changedSources(previousAnalysis).getOrElse {
      val previousSources: Set[VirtualFileRef] = previous.allSources.toSet

      log.debug(s"previous = $previous")
      log.debug(s"current source = $sources")

      new UnderlyingChanges[VirtualFileRef] {
        private val inBoth: Set[VirtualFile] =
          sources.filter(previousSources(_))
        val removed = previousSources -- inBoth
        val added = (sources -- inBoth).map(x => x: VirtualFileRef)
        val (changed0, unmodified0) =
          inBoth.partition(f => !equivS.equiv(previous.source(f), stamps.source(f)))
        val changed = changed0.map(x => x: VirtualFileRef)
        val unmodified = unmodified0.map(x => x: VirtualFileRef)
      }
    }

    val removedProducts: Set[VirtualFileRef] =
      lookup.removedProducts(previousAnalysis).getOrElse {
        previous.allProducts
          .filter(p => {
            // println(s"removedProducts? $p")
            !equivS.equiv(previous.product(p), stamps.product(p))
          })
          .toSet
      }

    val changedBinaries: Set[VirtualFileRef] = lookup.changedBinaries(previousAnalysis).getOrElse {
      val detectChange =
        isLibraryModified(
          enableShallowLookup,
          lookup,
          previous,
          stamps,
          previousRelations,
          converter,
          log
        )
      previous.allLibraries.filter(detectChange).toSet
    }

    val externalApiChanges: APIChanges = {
      val incrementalExternalChanges = {
        val previousAPIs = previousAnalysis.apis
        val externalFinder = lookupAnalyzedClass(_: String).getOrElse(APIs.emptyAnalyzedClass)
        detectAPIChanges(previousAPIs.allExternals, previousAPIs.externalAPI, externalFinder)
      }

      val changedExternalClassNames = incrementalExternalChanges.allModified.toSet
      if (!lookup.shouldDoIncrementalCompilation(changedExternalClassNames, previousAnalysis))
        new APIChanges(Nil)
      else incrementalExternalChanges
    }

    val init = InitialChanges(sourceChanges, removedProducts, changedBinaries, externalApiChanges)
    profiler.registerInitial(init)
    // log.debug(s"initial changes: $init")
    init
  }

  /**
   * Invalidates classes internally to a project after an incremental compiler run.
   *
   * @param relations The relations produced by the immediate previous incremental compiler cycle.
   * @param changes The changes produced by the immediate previous incremental compiler cycle.
   * @param recompiledClasses The immediately recompiled class names.
   * @param invalidateTransitively A flag that tells whether transitive invalidations should be
   *                               applied. This flag is only enabled when there have been more
   *                               than `incOptions.transitiveStep` incremental runs.
   * @param isScalaClass A function to know if a class name comes from a Scala source file or not.
   * @return A list of invalidated class names for the next incremental compiler run.
   */
  def invalidateAfterInternalCompilation(
      relations: Relations,
      changes: APIChanges,
      recompiledClasses: Set[String],
      invalidateTransitively: Boolean,
      isScalaClass: String => Boolean
  ): Set[String] = {
    val firstClassInvalidation: Set[String] = {
      if (invalidateTransitively) {
        // Invalidate by brute force (normally happens when we've done more than 3 incremental runs)
        val dependsOnClass = relations.memberRef.internal.reverse _
        transitiveDependencies(dependsOnClass, changes.allModified.toSet)
      } else {
        includeTransitiveInitialInvalidations(
          changes.allModified.toSet,
          changes.apiChanges.flatMap(invalidateClassesInternally(relations, _, isScalaClass)).toSet,
          findClassDependencies(_, relations)
        )
      }
    }

    // Invalidate classes linked with a class file that is produced by more than one source file
    val secondClassInvalidation = IncrementalCommon.invalidateNamesProducingSameClassFile(relations)
    if (secondClassInvalidation.nonEmpty)
      log.debug(s"Invalidated due to generated class file collision: ${secondClassInvalidation}")

    val newInvalidations = (firstClassInvalidation -- recompiledClasses) ++ secondClassInvalidation
    if (newInvalidations.isEmpty) {
      log.debug("No classes were invalidated.")
      Set.empty
    } else {
      val allInvalidatedClasses: Set[String] = firstClassInvalidation ++ secondClassInvalidation
      log.debug(s"Invalidated classes: ${allInvalidatedClasses.mkString(", ")}")
      allInvalidatedClasses
    }
  }

  /**
   * Returns the transitive class dependencies of an `initial` set of class names.
   *
   * Because the intermediate steps do not pull in cycles, this result includes the initial classes
   * if they are part of a cycle containing newly invalidated classes.
   */
  def transitiveDependencies(
      dependsOnClass: String => Set[String],
      initial: Set[String]
  ): Set[String] = {
    val transitiveWithInitial = IncrementalCommon.transitiveDeps(initial, log)(dependsOnClass)
    val transitivePartial =
      includeTransitiveInitialInvalidations(initial, transitiveWithInitial, dependsOnClass)
    log.debug("Final step, transitive dependencies:\n\t" + transitivePartial)
    transitivePartial
  }

  /** Invalidates classes and sources based on initially detected 'changes' to the sources, products, and dependencies.*/
  def invalidateInitial(
      previous: Relations,
      changes: InitialChanges
  ): (Set[String], Set[VirtualFileRef]) = {
    def classNames(srcs: Set[VirtualFileRef]): Set[String] = srcs.flatMap(previous.classNames)
    def toImmutableSet(srcs: java.util.Set[VirtualFileRef]): Set[VirtualFileRef] = {
      import scala.collection.JavaConverters.asScalaIteratorConverter
      srcs.iterator().asScala.toSet
    }

    val srcChanges = changes.internalSrc
    val removedSrcs = toImmutableSet(srcChanges.getRemoved)
    val modifiedSrcs = toImmutableSet(srcChanges.getChanged)
    val addedSrcs = toImmutableSet(srcChanges.getAdded)
    IncrementalCommon.checkAbsolute(addedSrcs)

    val removedClasses = classNames(removedSrcs)
    val dependentOnRemovedClasses = removedClasses.flatMap(previous.memberRef.internal.reverse)
    val modifiedClasses = classNames(modifiedSrcs)
    val invalidatedClasses = removedClasses ++ dependentOnRemovedClasses ++ modifiedClasses

    val byProduct = changes.removedProducts.flatMap(previous.produced)
    val byLibraryDep = changes.libraryDeps.flatMap(previous.usesLibrary)
    val byExtSrcDep = {
      // Invalidate changes
      val isScalaSource = IncrementalCommon.comesFromScalaSource(previous) _
      changes.external.apiChanges.iterator.flatMap { externalAPIChange =>
        invalidateClassesExternally(previous, externalAPIChange, isScalaSource)
      }.toSet
    }

    val allInvalidatedClasses = invalidatedClasses ++ byExtSrcDep
    val allInvalidatedSourcefiles = addedSrcs ++ modifiedSrcs ++ byProduct ++ byLibraryDep

    if (previous.allSources.isEmpty)
      log.debug("Full compilation, no sources in previous analysis.")
    else if (allInvalidatedClasses.isEmpty && allInvalidatedSourcefiles.isEmpty)
      log.debug("No changes")
    else
      log.debug(
        "\nInitial source changes: \n\tremoved:" + removedSrcs + "\n\tadded: " + addedSrcs + "\n\tmodified: " + modifiedSrcs +
          "\nInvalidated products: " + changes.removedProducts +
          "\nExternal API changes: " + changes.external +
          "\nModified binary dependencies: " + changes.libraryDeps +
          "\nInitial directly invalidated classes: " + invalidatedClasses +
          "\n\nSources indirectly invalidated by:" +
          "\n\tproduct: " + byProduct +
          "\n\tbinary dep: " + byLibraryDep +
          "\n\texternal source: " + byExtSrcDep
      )

    (allInvalidatedClasses, allInvalidatedSourcefiles)
  }

  /**
   * Invalidates inheritance dependencies, transitively.  Then, invalidates direct dependencies.  Finally, excludes initial dependencies not
   * included in a cycle with newly invalidated classes.
   */
  def invalidateClasses(
      previous: Relations,
      changes: APIChanges,
      isScalaClass: String => Boolean
  ): Set[String] = {
    includeTransitiveInitialInvalidations(
      changes.allModified.toSet,
      changes.apiChanges.flatMap(invalidateClassesInternally(previous, _, isScalaClass)).toSet,
      findClassDependencies(_, previous)
    )
  }

  /**
   * Conditionally include initial classes that are dependencies of newly invalidated classes.
   * Initial classes included in this step can be because of a cycle, but not always.
   */
  /**
   * Returns the invalidations that are the result of the `currentInvalidations` + the
   * `previousInvalidations` that depend transitively on `currentInvalidations`.
   *
   * We do this step on every incremental compiler iteration of a project where
   * `previousInvalidations` typically refers to the classes invalidated in the
   * previous incremental compiler cycle.
   *
   * @param previousInvalidations
   * @param currentInvalidations
   * @param findClassDependencies
   * @return
   */
  private[this] def includeTransitiveInitialInvalidations(
      previousInvalidations: Set[String],
      currentInvalidations: Set[String],
      findClassDependencies: String => Set[String]
  ): Set[String] = {
    val newInvalidations = currentInvalidations -- previousInvalidations
    log.debug("New invalidations:\n\t" + newInvalidations)

    val newTransitiveInvalidations =
      IncrementalCommon.transitiveDeps(newInvalidations, log)(findClassDependencies)
    // Include the initial invalidations that are present in the transitive new invalidations
    val includedInitialInvalidations = newTransitiveInvalidations & previousInvalidations

    log.debug(
      "Previously invalidated, but (transitively) depend on new invalidations:\n\t" + includedInitialInvalidations
    )
    newInvalidations ++ includedInitialInvalidations
  }

  /**
   * Logs API changes using debug-level logging. The API are obtained using the APIDiff class.
   *
   * NOTE: This method creates a new APIDiff instance on every invocation.
   */
  private def logApiChanges(
      apiChanges: Iterable[APIChange],
      oldAPIMapping: String => AnalyzedClass,
      newAPIMapping: String => AnalyzedClass
  ): Unit = {
    val contextSize = options.apiDiffContextSize
    try {
      val wrappedLog = new PrefixingLogger("[diff] ")(log)
      val apiDiff = new APIDiff
      apiChanges foreach {
        case APIChangeDueToMacroDefinition(src) =>
          wrappedLog.debug(s"Detected API change because $src contains a macro definition.")
        case TraitPrivateMembersModified(modifiedClass) =>
          wrappedLog.debug(s"Detect change in private members of trait ${modifiedClass}.")
        case apiChange: NamesChange =>
          val src = apiChange.modifiedClass
          val oldApi = oldAPIMapping(src)
          val newApi = newAPIMapping(src)
          val apiUnifiedPatch =
            apiDiff.generateApiDiff(src.toString, oldApi.api, newApi.api, contextSize)
          wrappedLog.debug(s"Detected a change in a public API ($src):\n$apiUnifiedPatch")
      }
    } catch {
      case e: Exception =>
        log.error("An exception has been thrown while trying to dump an api diff.")
        log.trace(e)
    }
  }

  /**
   * Add package objects that inherit from the set of invalidated classes to avoid
   * "class file needed by package is missing" compilation errors.
   *
   * This might be to conservative. We probably only need the package objects for packages
   * of invalidated classes.
   *
   * @param invalidatedClasses The set of invalidated classes.
   * @param relations The current relations.
   * @param apis The current APIs information.
   * @return The set of invalidated classes + the set of package objects.
   */
  protected def invalidatedPackageObjects(
      invalidatedClasses: Set[String],
      relations: Relations,
      apis: APIs
  ): Set[String]

  /**
   * Find an API change between the `previous` and `current` class representations of `className`.
   *
   * @param className The class name that identifies both analyzed classes.
   * @param previous The analyzed class that comes from the previous analysis.
   * @param current The analyzed class that comes from the current analysis.
   * @return An optional API change detected between `previous` and `current`.
   */
  protected def findAPIChange(
      className: String,
      previous: AnalyzedClass,
      current: AnalyzedClass
  ): Option[APIChange]

  /**
   * Finds the class dependencies of `className` given an instance of [[Relations]].
   *
   * @param className The class name from which we detect dependencies.
   * @param relations The instance of relations.
   * @return A collection of classes that depend on `className`.
   */
  protected def findClassDependencies(
      className: String,
      relations: Relations
  ): Set[String]

  /**
   * Invalidates a set of class names given the current relations and an internal API change.
   *
   * This step happens in every cycle of the incremental compiler as it is required to know
   * what classes were invalidated given the previous incremental compiler run.
   *
   * @param currentRelations  The relations from the previous analysis file of the compiled project.
   * @param externalAPIChange The internal API change detected by [[invalidateAfterInternalCompilation()]].
   * @param isScalaClass      A function that tell us whether a class is defined in a Scala file or not.
   */
  protected def invalidateClassesInternally(
      relations: Relations,
      change: APIChange,
      isScalaClass: String => Boolean
  ): Set[String]

  /**
   * Invalidates a set of class names given the current relations and an external API change
   * that has been detected in upstream projects. This step only happens in `invalidateInitial`
   * because that's where external changes need to be detected and properly invalidated.
   *
   * @param currentRelations The relations from the previous analysis file of the compiled project.
   * @param externalAPIChange The external API change detected by [[detectInitialChanges()]].
   * @param isScalaClass A function that tell us whether a class is defined in a Scala file or not.
   */
  protected def invalidateClassesExternally(
      currentRelations: Relations,
      externalAPIChange: APIChange,
      isScalaClass: String => Boolean
  ): Set[String]
}

object IncrementalCommon {

  /** Tell if given class names comes from a Scala source file or not by inspecting relations. */
  def comesFromScalaSource(
      previous: Relations,
      current: Option[Relations] = None
  )(className: String): Boolean = {
    val previousSourcesWithClassName = previous.classes.reverse(className)
    val newSourcesWithClassName = current.map(_.classes.reverse(className)).getOrElse(Set.empty)
    if (previousSourcesWithClassName.isEmpty && newSourcesWithClassName.isEmpty)
      sys.error(s"Fatal Zinc error: no entry for class $className in classes relation.")
    else {
      // Makes sure that the dependency doesn't possibly come from Java
      previousSourcesWithClassName.forall(src => APIUtil.isScalaSourceName(src.id)) &&
      newSourcesWithClassName.forall(src => APIUtil.isScalaSourceName(src.id))
    }
  }

  /** Invalidate all classes that claim to produce the same class file as another class. */
  def invalidateNamesProducingSameClassFile(merged: Relations): Set[String] = {
    merged.srcProd.reverseMap.flatMap {
      case (_, sources) => if (sources.size > 1) sources.flatMap(merged.classNames(_)) else Nil
    }.toSet
  }

  /**
   * - If the classpath hash has NOT changed, check if there's been name shadowing
   *   by looking up the library-associated class names into the Analysis file.
   * - If the classpath hash has changed, check if the library-associated classes
   *   are still associated with the same library.
   *   This would avoid recompiling everything when classpath changes.
   *
   * @param lookup A lookup instance to ask questions about the classpath.
   * @param previousStamps The stamps associated with the previous compilation.
   * @param currentStamps The stamps associated with the current compilation.
   * @param previousRelations The relation from the previous compiler iteration.
   * @param log A logger.
   * @param equivS An equivalence function to compare stamps.
   * @return
   */
  def isLibraryModified(
      skipClasspathLookup: Boolean,
      lookup: Lookup,
      previousStamps: Stamps,
      currentStamps: ReadStamps,
      previousRelations: Relations,
      converter: FileConverter,
      log: Logger
  )(implicit equivS: Equiv[XStamp]): VirtualFileRef => Boolean = { (binaryFile: VirtualFileRef) =>
    {
      def invalidateBinary(reason: String): Boolean = {
        log.debug(s"Invalidating '$binaryFile' because $reason"); true
      }

      def compareStamps(previousFile: VirtualFileRef, currentFile: VirtualFileRef): Boolean = {
        val previousStamp = previousStamps.library(previousFile)
        val currentStamp = currentStamps.library(currentFile)
        if (equivS.equiv(previousStamp, currentStamp)) false
        else invalidateBinary(s"$previousFile ($previousStamp) != $currentFile ($currentStamp)")
      }

      def isLibraryChanged(file: VirtualFileRef): Boolean = {
        def compareOriginClassFile(className: String, classpathEntry: VirtualFileRef): Boolean = {
          if (classpathEntry.id.endsWith(".jar") &&
              (converter.toPath(classpathEntry).toString != converter.toPath(file).toString))
            invalidateBinary(s"${className} is now provided by ${classpathEntry}")
          else compareStamps(file, classpathEntry)
        }

        val classNames = previousRelations.libraryClassNames(file)
        classNames.exists { binaryClassName =>
          if (lookup.changedClasspathHash.isEmpty) {
            // If classpath is not changed, the only possible change needs to come from same project
            lookup.lookupAnalysis(binaryClassName) match {
              case None => false
              // Most of the cases this is a build tool misconfiguration when using Zinc
              case Some(a) => invalidateBinary(s"${binaryClassName} came from analysis $a")
            }
          } else {
            // Find
            lookup.lookupOnClasspath(binaryClassName) match {
              case None =>
                invalidateBinary(s"could not find class $binaryClassName on the classpath.")
              case Some(classpathEntry) => compareOriginClassFile(binaryClassName, classpathEntry)
            }
          }
        }
      }

      if (skipClasspathLookup) compareStamps(binaryFile, binaryFile)
      else isLibraryChanged(binaryFile)
    }
  }

  def transitiveDeps[T](
      nodes: Iterable[T],
      log: Logger,
      logging: Boolean = true
  )(dependencies: T => Iterable[T]): Set[T] = {
    val visited = new collection.mutable.HashSet[T]
    def all(from: T, tos: Iterable[T]): Unit = tos.foreach(to => visit(from, to))
    def visit(from: T, to: T): Unit = {
      if (!visited.contains(to)) {
        if (logging) log.debug(s"Including $to by $from")
        visited += to
        all(to, dependencies(to))
      }
    }

    if (logging) log.debug(s"Initial set of included nodes: ${nodes.mkString(", ")}")
    nodes.foreach { start =>
      visited += start
      all(start, dependencies(start))
    }
    visited.toSet
  }

  /**
   * Check that a collection of files are absolute and not relative.
   *
   * For legacy reasons, the logic to check the absolute path of source files has been
   * implemented in the core invalidation algorithm logic. It remains here as there are
   * more important things to do than fixing this issue.
   *
   * @param addedSources
   */
  def checkAbsolute(addedSources: Iterable[VirtualFileRef]): Unit = {
    if (addedSources.isEmpty) ()
    else {
      // addedSources.filterNot(_.isAbsolute).toList match {
      //   case first :: more =>
      //     val fileStrings = more match {
      //       case Nil      => first.toString
      //       case x :: Nil => s"$first and $x"
      //       case _        => s"$first and ${more.size} others"
      //     }
      //     sys.error(s"Expected absolute source files instead of ${fileStrings}.")
      //   case Nil => ()
      // }
    }
  }

  def emptyChanges: DependencyChanges = new DependencyChanges {
    override val modifiedLibraries = new Array[VirtualFileRef](0)
    override val modifiedClasses = new Array[String](0)
    override def isEmpty = true
  }

  /**
   * Prunes from the analysis and deletes the class files of `invalidatedSources`.
   *
   * @param invalidatedSources The set of invalidated sources.
   * @param previous The previous analysis instance.
   * @param classfileManager The class file manager.
   * @return An instance of analysis that doesn't contain the invalidated sources.
   */
  def pruneClassFilesOfInvalidations(
      invalidatedSources: Set[VirtualFile],
      previous: Analysis,
      classfileManager: XClassFileManager,
      converter: FileConverter
  ): Analysis = {
    val products = invalidatedSources.flatMap(previous.relations.products).toList
    classfileManager.delete(products.map(converter.toVirtualFile(_)).toArray)
    previous -- invalidatedSources
  }
}
