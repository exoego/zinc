/* sbt -- Simple Build Tool
 * Copyright 2010  Mark Harrah
 */
package sbt
package inc

import java.io.File
import Relations.Source
import Relations.ClassDependencies
import xsbti.api.{ Source => APISource }
import xsbti.DependencyContext
import xsbti.DependencyContext._

/**
 * Provides mappings between source files, generated classes (products), and binaries.
 * Dependencies that are tracked include internal: a dependency on a source in the same compilation group (project),
 * external: a dependency on a source in another compilation group (tracked as the name of the class),
 * binary: a dependency on a class or jar file not generated by a source file in any tracked compilation group,
 * inherited: a dependency that resulted from a public template inheriting,
 * direct: any type of dependency, including inheritance.
 */
trait Relations {
  /** All sources _with at least one product_ . */
  def allSources: collection.Set[File]

  /** All products associated with sources. */
  def allProducts: collection.Set[File]

  /** All files that are recorded as a binary dependency of a source file.*/
  def allBinaryDeps: collection.Set[File]

  /** All files in this compilation group (project) that are recorded as a source dependency of a source file in this group.*/
  @deprecated("Class-based dependency tracking")
  def allInternalSrcDeps: collection.Set[File]

  /** All files in another compilation group (project) that are recorded as a source dependency of a source file in this group.*/
  def allExternalDeps: collection.Set[String]

  /** Fully qualified names of classes generated from source file `src`. */
  def classNames(src: File): Set[String]

  /** Source files that generated a class with the given fully qualified `name`. This is typically a set containing a single file. */
  def definesClass(name: String): Set[File]

  /** The classes that were generated for source file `src`. */
  def products(src: File): Set[File]
  /** The source files that generated class file `prod`.  This is typically a set containing a single file. */
  def produced(prod: File): Set[File]

  /** The binary dependencies for the source file `src`. */
  def binaryDeps(src: File): Set[File]
  /** The source files that depend on binary file `dep`. */
  def usesBinary(dep: File): Set[File]

  /** Internal source dependencies for `src`.  This includes both direct and inherited dependencies.  */
  def internalSrcDeps(src: File): Set[File]
  /** Internal source files that depend on internal source `dep`.  This includes both direct and inherited dependencies.  */
  def usesInternalSrc(dep: File): Set[File]

  /** External source dependencies that internal source file `src` depends on.  This includes both direct and inherited dependencies.  */
  def externalDeps(src: File): Set[String]
  /** Internal source dependencies that depend on external source file `dep`.  This includes both direct and inherited dependencies.  */
  def usesExternal(dep: String): Set[File]

  private[inc] def usedNames(src: File): Set[String]

  /**
   * Names (fully qualified, at pickler phase) of classes defined in source
   * file `src`.
   */
  private[inc] def declaredClassNames(src: File): Set[String]

  /**
   * Records that the file `src` generates products `products`, has internal dependencies `internalDeps`,
   * has external dependencies `externalDeps` and binary dependencies `binaryDeps`.
   */
  def addSource(src: File,
    products: Iterable[File],
    classes: Iterable[(String, String)],
    internalDeps: Iterable[InternalDependency],
    externalDeps: Iterable[ExternalDependency],
    binaryDeps: Iterable[(File, String, Stamp)]): Relations = {
    addProducts(src, products).addClasses(src, classes).
      addInternalSrcDeps(src, internalDeps).addExternalDeps(src, externalDeps).
      addBinaryDeps(src, binaryDeps)
  }

  /**
   * Records all the products `prods` generated by `src`
   */
  private[inc] def addProducts(src: File, prods: Iterable[File]): Relations

  /**
   * Records all the classes `classes` generated by `src`
   *
   * a single entry in `classes` collection is `(src class name, binary class name)`
   */
  private[inc] def addClasses(src: File, classes: Iterable[(String, String)]): Relations

  /**
   * Records all the internal source dependencies `deps` of `src`
   */
  private[inc] def addInternalSrcDeps(src: File, deps: Iterable[InternalDependency]): Relations

  /**
   * Records all the external dependencies `deps` of `src`
   */
  private[inc] def addExternalDeps(src: File, deps: Iterable[ExternalDependency]): Relations

  /**
   * Records all the binary dependencies `deps` of `src`
   */
  private[inc] def addBinaryDeps(src: File, deps: Iterable[(File, String, Stamp)]): Relations

  private[inc] def addUsedName(src: File, name: String): Relations

  private[inc] def addDeclaredClass(src: File, className: String): Relations

  /** Concatenates the two relations. Acts naively, i.e., doesn't internalize external deps on added files. */
  def ++(o: Relations): Relations

  /** Drops all dependency mappings a->b where a is in `sources`. Acts naively, i.e., doesn't externalize internal deps on removed files. */
  def --(sources: Iterable[File]): Relations

  /** The relation between internal sources and generated class files. */
  def srcProd: Relation[File, File]

  /** The dependency relation between internal sources and binaries. */
  def binaryDep: Relation[File, File]

  /** The dependency relation between internal classes.*/
  def internalClassDep: Relation[String, String]

  /** The dependency relation between internal and external classes.*/
  def externalClassDep: Relation[String, String]

  /** All the internal dependencies */
  private[inc] def internalDependencies: InternalDependencies

  /** All the external dependencies */
  private[inc] def externalDependencies: ExternalDependencies

  /**
   * The class dependency relation between classes introduced by member reference.
   *
   * NOTE: All inheritance dependencies are included in this relation because in order to
   * inherit from a member you have to refer to it. If you check documentation of `inheritance`
   * you'll see that there's small oddity related to traits being the first parent of a
   * class/trait that results in additional parents being introduced due to normalization.
   * This relation properly accounts for that so the invariant that `memberRef` is a superset
   * of `inheritance` is preserved.
   */
  private[inc] def memberRef: ClassDependencies

  /**
   * The class dependency relation between classes introduced by inheritance.
   * The dependency by inheritance is introduced when a template (class or trait) mentions
   * a given type in a parent position.
   *
   * NOTE: Due to an oddity in how Scala's type checker works there's one unexpected dependency
   * on a class being introduced. An example illustrates the best the problem. Let's consider
   * the following structure:
   *
   * trait A extends B
   * trait B extends C
   * trait C extends D
   * class D
   *
   * We are interested in dependencies by inheritance of `A`. One would expect it to be just `B`
   * but the answer is `B` and `D`. The reason is because Scala's type checker performs a certain
   * normalization so the first parent of a type is a class. Therefore the example above is normalized
   * to the following form:
   *
   * trait A extends D with B
   * trait B extends D with C
   * trait C extends D
   * class D
   *
   * Therefore if you inherit from a trait you'll get an additional dependency on a class that is
   * resolved transitively. You should not rely on this behavior, though.
   *
   */
  private[inc] def inheritance: ClassDependencies

  /** The dependency relations between sources.  These include both direct and inherited dependencies.*/
  def direct: Source

  /** The inheritance dependency relations between sources.*/
  def publicInherited: Source

  /** The relation between a source file and the fully qualified names of classes generated from it.*/
  def classes: Relation[File, String]

  /**
   * Flag which indicates whether given Relations object supports operations needed by name hashing algorithm.
   *
   * At the moment the list includes the following operations:
   *
   *   - memberRef: ClassDependencies
   *   - inheritance: ClassDependencies
   *
   * The `memberRef` and `inheritance` implement a new style class dependency tracking. When this flag is
   * enabled access to `direct` and `publicInherited` relations is illegal and will cause runtime exception
   * being thrown. That is done as an optimization that prevents from storing two overlapping sets of
   * dependencies.
   *
   * Conversely, when `nameHashing` flag is disabled access to `memberRef` and `inheritance`
   * relations is illegal and will cause runtime exception being thrown.
   */
  private[inc] def nameHashing: Boolean
  /**
   * Relation between source files and _unqualified_ term and type names used in given source file.
   */
  private[inc] def names: Relation[File, String]

  /**
   * The relation between a source file and names of classes declared in it.
   *
   * Names of classes are fully qualified names as seen at pickler phase (i.e. before flattening).
   * Objects are represented as object name with dollar sign appended to it.
   */
  def declaredClasses: Relation[File, String]
}

object Relations {
  private[inc] val allRelations: List[RelationDescriptor[_, _]] = {
    List(
      FFRelationDescriptor("products", _.srcProd),
      FFRelationDescriptor("binary dependencies", _.binaryDep),
      // NameHashing doesn't provide direct dependencies
      FFRelationDescriptor("direct source dependencies", r => Relations.emptySource.internal),
      // NameHashing doesn't provide direct dependencies
      FSRelationDescriptor("direct external dependencies", r => Relations.emptySource.external),
      // NameHashing doesn't provide public inherited dependencies
      FFRelationDescriptor("public inherited source dependencies", r => Relations.emptySource.internal),
      // NameHashing doesn't provide public inherited dependencies
      FSRelationDescriptor("public inherited external dependencies", r => Relations.emptySource.external),
      SSRelationDescriptor("member reference internal dependencies", _.memberRef.internal),
      SSRelationDescriptor("member reference external dependencies", _.memberRef.external),
      SSRelationDescriptor("inheritance internal dependencies", _.inheritance.internal),
      SSRelationDescriptor("inheritance external dependencies", _.inheritance.external),
      FSRelationDescriptor("class names", _.classes),
      FSRelationDescriptor("used names", _.names),
      FSRelationDescriptor("declared classes", _.declaredClasses)
    )
  }
  /**
   * Reconstructs a Relations from a list of Relation
   * The order in which the relations are read matters and is defined by `existingRelations`.
   */
  def construct(nameHashing: Boolean, relations: List[Relation[_, _]]) =
    relations match {
      case p :: bin :: di :: de :: pii :: pie :: mri :: mre :: ii :: ie :: cn :: un :: dc :: Nil =>
        val srcProd = p.asInstanceOf[Relation[File, File]]
        val binaryDep = bin.asInstanceOf[Relation[File, File]]
        val directSrcDeps = makeSource(di.asInstanceOf[Relation[File, File]], de.asInstanceOf[Relation[File, String]])
        val publicInheritedSrcDeps = makeSource(pii.asInstanceOf[Relation[File, File]], pie.asInstanceOf[Relation[File, String]])
        val memberRefSrcDeps = makeClassDependencies(mri.asInstanceOf[Relation[String, String]], mre.asInstanceOf[Relation[String, String]])
        val classes = cn.asInstanceOf[Relation[File, String]]
        val names = un.asInstanceOf[Relation[File, String]]
        val declaredClasses = dc.asInstanceOf[Relation[File, String]]

        // we don't check for emptiness of publicInherited/inheritance relations because
        // we assume that invariant that says they are subsets of direct/memberRef holds
        assert(nameHashing || (memberRefSrcDeps == emptyClassDependencies), "When name hashing is disabled the `memberRef` relation should be empty.")
        assert(!nameHashing || (directSrcDeps == emptySource), "When name hashing is enabled the `direct` relation should be empty.")

        if (nameHashing) {
          val internal = InternalDependencies(Map(DependencyByMemberRef -> mri.asInstanceOf[Relation[String, String]],
            DependencyByInheritance -> ii.asInstanceOf[Relation[String, String]]))
          val external = ExternalDependencies(Map(DependencyByMemberRef -> mre.asInstanceOf[Relation[String, String]],
            DependencyByInheritance -> ie.asInstanceOf[Relation[String, String]]))
          Relations.make(srcProd, binaryDep, internal, external, classes, names, declaredClasses)
        } else {
          assert(names.all.isEmpty, s"When `nameHashing` is disabled `names` relation should be empty: $names")
          Relations.make(srcProd, binaryDep, directSrcDeps, publicInheritedSrcDeps, classes)
        }
      case _ => throw new java.io.IOException(s"Expected to read ${allRelations.length} relations but read ${relations.length}.")
    }

  /** Tracks internal and external source dependencies for a specific dependency type, such as direct or inherited.*/
  final class Source private[sbt] (val internal: Relation[File, File], val external: Relation[File, String]) {
    def addInternal(source: File, dependsOn: Iterable[File]): Source = new Source(internal + (source, dependsOn), external)
    def addExternal(source: File, dependsOn: Iterable[String]): Source = new Source(internal, external + (source, dependsOn))
    /** Drops all dependency mappings from `sources`. Acts naively, i.e., doesn't externalize internal deps on removed files.*/
    def --(sources: Iterable[File]): Source = new Source(internal -- sources, external -- sources)
    def ++(o: Source): Source = new Source(internal ++ o.internal, external ++ o.external)

    override def equals(other: Any) = other match {
      case o: Source => internal == o.internal && external == o.external
      case _         => false
    }

    override def hashCode = (internal, external).hashCode
  }

  /** Tracks internal and external source dependencies for a specific dependency type, such as direct or inherited.*/
  private[inc] final class ClassDependencies(val internal: Relation[String, String], val external: Relation[String, String]) {
    def addInternal(className: String, dependsOn: Iterable[String]): ClassDependencies =
      new ClassDependencies(internal + (className, dependsOn), external)
    def addExternal(className: String, dependsOn: Iterable[String]): ClassDependencies =
      new ClassDependencies(internal, external + (className, dependsOn))
    /** Drops all dependency mappings from `sources`. Acts naively, i.e., doesn't externalize internal deps on removed files.*/
    def --(classNames: Iterable[String]): ClassDependencies =
      new ClassDependencies(internal -- classNames, external -- classNames)
    def ++(o: ClassDependencies): ClassDependencies = new ClassDependencies(internal ++ o.internal, external ++ o.external)

    override def equals(other: Any) = other match {
      case o: ClassDependencies => internal == o.internal && external == o.external
      case _                    => false
    }

    override def hashCode = (internal, external).hashCode
  }

  private[sbt] def getOrEmpty[A, B, K](m: Map[K, Relation[A, B]], k: K): Relation[A, B] = m.getOrElse(k, Relation.empty)

  private[this] lazy val e = Relation.empty[File, File]
  private[this] lazy val estr = Relation.empty[File, String]
  private[this] lazy val estrstr = Relation.empty[String, String]
  private[this] lazy val es = new Source(e, estr)

  def emptySource: Source = es
  private[inc] lazy val emptyClassDependencies: ClassDependencies = new ClassDependencies(estrstr, estrstr)
  def empty: Relations = empty(nameHashing = IncOptions.nameHashingDefault)
  private[inc] def empty(nameHashing: Boolean): Relations =
    if (nameHashing)
      new MRelationsNameHashing(e, e, InternalDependencies.empty, ExternalDependencies.empty, estr, estr, estr)
    else
      throw new UnsupportedOperationException("Turning off name hashing is not supported in class-based dependency tracking")

  def make(srcProd: Relation[File, File], binaryDep: Relation[File, File], direct: Source, publicInherited: Source, classes: Relation[File, String]): Relations =
    throw new UnsupportedOperationException("Turning off name hashing is not supported in class-based dependency tracking")

  private[inc] def make(srcProd: Relation[File, File], binaryDep: Relation[File, File],
    internalDependencies: InternalDependencies, externalDependencies: ExternalDependencies,
    classes: Relation[File, String], names: Relation[File, String],
    declaredClasses: Relation[File, String]): Relations =
    new MRelationsNameHashing(srcProd, binaryDep, internalDependencies = internalDependencies,
      externalDependencies = externalDependencies, classes, names, declaredClasses)
  def makeSource(internal: Relation[File, File], external: Relation[File, String]): Source = new Source(internal, external)
  private[inc] def makeClassDependencies(internal: Relation[String, String], external: Relation[String, String]): ClassDependencies =
    new ClassDependencies(internal, external)
}

private object DependencyCollection {
  /**
   * Combine `m1` and `m2` such that the result contains all the dependencies they represent.
   * `m1` is expected to be smaller than `m2`.
   */
  def joinMaps[T](m1: Map[DependencyContext, Relation[String, T]], m2: Map[DependencyContext, Relation[String, T]]) =
    m1.foldLeft(m2) { case (tmp, (key, values)) => tmp.updated(key, tmp.getOrElse(key, Relation.empty) ++ values) }
}

private object InternalDependencies {
  /**
   * Constructs an empty `InteralDependencies`
   */
  def empty = InternalDependencies(Map.empty)
}

private case class InternalDependencies(dependencies: Map[DependencyContext, Relation[String, String]]) {
  /**
   * Adds `dep` to the dependencies
   */
  def +(dep: InternalDependency): InternalDependencies =
    InternalDependencies(dependencies.updated(dep.context,
      dependencies.getOrElse(dep.context, Relation.empty) + (dep.sourceClassName, dep.targetClassName)))

  /**
   * Adds all `deps` to the dependencies
   */
  def ++(deps: Iterable[InternalDependency]): InternalDependencies = deps.foldLeft(this)(_ + _)
  def ++(deps: InternalDependencies): InternalDependencies =
    InternalDependencies(DependencyCollection.joinMaps(dependencies, deps.dependencies))

  /**
   * Removes all dependencies from `sources` to another file from the dependencies
   */
  def --(classes: Iterable[String]): InternalDependencies = {
    InternalDependencies(dependencies.mapValues(_ -- classes).filter(_._2.size > 0))
  }
}

private object ExternalDependencies {
  /**
   * Constructs an empty `ExternalDependencies`
   */
  def empty = ExternalDependencies(Map.empty)
}

private case class ExternalDependencies(dependencies: Map[DependencyContext, Relation[String, String]]) {
  /**
   * Adds `dep` to the dependencies
   */
  def +(dep: ExternalDependency): ExternalDependencies =
    ExternalDependencies(dependencies.updated(dep.context,
      dependencies.getOrElse(dep.context, Relation.empty) + (dep.sourceClassName, dep.targetClassName))
    )

  /**
   * Adds all `deps` to the dependencies
   */
  def ++(deps: Iterable[ExternalDependency]): ExternalDependencies = deps.foldLeft(this)(_ + _)
  def ++(deps: ExternalDependencies): ExternalDependencies = ExternalDependencies(DependencyCollection.joinMaps(dependencies, deps.dependencies))

  /**
   * Removes all dependencies from `sources` to another file from the dependencies
   */
  def --(classNames: Iterable[String]): ExternalDependencies =
    ExternalDependencies(dependencies.mapValues(_ -- classNames).filter(_._2.size > 0))
}

private[inc] sealed trait RelationDescriptor[A, B] {
  val header: String
  val selectCorresponding: Relations => Relation[A, B]
  def firstWrite(a: A): String
  def firstRead(s: String): A
  def secondWrite(b: B): String
  def secondRead(s: String): B
}

private[inc] case class FFRelationDescriptor(header: String, selectCorresponding: Relations => Relation[File, File])
    extends RelationDescriptor[File, File] {
  override def firstWrite(a: File): String = a.toString
  override def secondWrite(b: File): String = b.toString
  override def firstRead(s: String): File = new File(s)
  override def secondRead(s: String): File = new File(s)
}

private[inc] case class FSRelationDescriptor(header: String, selectCorresponding: Relations => Relation[File, String])
    extends RelationDescriptor[File, String] {
  override def firstWrite(a: File): String = a.toString
  override def secondWrite(b: String): String = b
  override def firstRead(s: String): File = new File(s)
  override def secondRead(s: String): String = s
}

private[inc] case class SFRelationDescriptor(header: String, selectCorresponding: Relations => Relation[String, File])
    extends RelationDescriptor[String, File] {
  override def firstWrite(a: String): String = a
  override def secondWrite(b: File): String = b.toString
  override def firstRead(s: String): String = s
  override def secondRead(s: String): File = new File(s)
}

private[inc] case class SSRelationDescriptor(header: String, selectCorresponding: Relations => Relation[String, String])
    extends RelationDescriptor[String, String] {
  override def firstWrite(a: String): String = a
  override def secondWrite(b: String): String = b
  override def firstRead(s: String): String = s
  override def secondRead(s: String): String = s
}

/**
 * An abstract class that contains common functionality inherited by two implementations of Relations trait.
 *
 * A little note why we have two different implementations of Relations trait. This is needed for the time
 * being when we are slowly migrating to the new invalidation algorithm called "name hashing" which requires
 * some subtle changes to dependency tracking. For some time we plan to keep both algorithms side-by-side
 * and have a runtime switch which allows to pick one. So we need logic for both old and new dependency
 * tracking to be available. That's exactly what two subclasses of MRelationsCommon implement. Once name
 * hashing is proven to be stable and reliable we'll phase out the old algorithm and the old dependency tracking
 * logic.
 *
 * `srcProd` is a relation between a source file and a product: (source, product).
 * Note that some source files may not have a product and will not be included in this relation.
 *
 * `binaryDeps` is a relation between a source file and a binary dependency: (source, binary dependency).
 *   This only includes dependencies on classes and jars that do not have a corresponding source/API to track instead.
 *   A class or jar with a corresponding source should only be tracked in one of the source dependency relations.
 *
 * `classes` is a relation between a source file and its generated fully-qualified class names.
 */
private abstract class MRelationsCommon(val srcProd: Relation[File, File], val binaryDep: Relation[File, File],
    val classes: Relation[File, String], override val declaredClasses: Relation[File, String]) extends Relations {
  def allSources: collection.Set[File] = srcProd._1s

  def allProducts: collection.Set[File] = srcProd._2s
  def allBinaryDeps: collection.Set[File] = binaryDep._2s
  def allInternalSrcDeps: collection.Set[File] = internalClassDep._2s.flatMap(declaredClasses.reverse)
  def allExternalDeps: collection.Set[String] = externalClassDep._2s

  def classNames(src: File): Set[String] = classes.forward(src)
  def definesClass(name: String): Set[File] = classes.reverse(name)

  def products(src: File): Set[File] = srcProd.forward(src)
  def produced(prod: File): Set[File] = srcProd.reverse(prod)

  def binaryDeps(src: File): Set[File] = binaryDep.forward(src)
  def usesBinary(dep: File): Set[File] = binaryDep.reverse(dep)

  def internalSrcDeps(src: File): Set[File] =
    declaredClasses.forward(src).flatMap(internalClassDep.forward).flatMap(declaredClasses.reverse)
  def usesInternalSrc(dep: File): Set[File] =
    declaredClasses.forward(dep).flatMap(internalClassDep.reverse).flatMap(declaredClasses.reverse)

  def externalDeps(src: File): Set[String] = declaredClasses.forward(src).flatMap(externalClassDep.forward)
  def usesExternal(dep: String): Set[File] = externalClassDep.reverse(dep).flatMap(declaredClasses.reverse)

  def usedNames(src: File): Set[String] = names.forward(src)

  /** Making large Relations a little readable. */
  private val userDir = sys.props("user.dir").stripSuffix("/") + "/"
  private def nocwd(s: String) = s stripPrefix userDir
  private def line_s(kv: (Any, Any)) = "    " + nocwd("" + kv._1) + " -> " + nocwd("" + kv._2) + "\n"
  protected def relation_s(r: Relation[_, _]) = (
    if (r.forwardMap.isEmpty) "Relation [ ]"
    else (r.all.toSeq map line_s sorted) mkString ("Relation [\n", "", "]")
  )
}

/**
 * This class implements Relations trait with support for tracking of `memberRef` and `inheritance` class
 * dependencies. Therefore this class implements the new (compared to sbt 0.13.0) dependency tracking logic
 * needed by the name hashing invalidation algorithm.
 */
private class MRelationsNameHashing(srcProd: Relation[File, File], binaryDep: Relation[File, File],
    val internalDependencies: InternalDependencies,
    val externalDependencies: ExternalDependencies,
    classes: Relation[File, String],
    val names: Relation[File, String],
    declaredClasses: Relation[File, String]) extends MRelationsCommon(srcProd, binaryDep, classes, declaredClasses) {
  def direct: Source =
    throw new UnsupportedOperationException("The `direct` source dependencies relation is not supported " +
      "when `nameHashing` flag is disabled.")
  def publicInherited: Source =
    throw new UnsupportedOperationException("The `publicInherited` source dependencies relation is not supported " +
      "when `nameHashing` flag is disabled.")

  val nameHashing: Boolean = true

  def internalClassDep: Relation[String, String] = memberRef.internal
  def externalClassDep: Relation[String, String] = memberRef.external

  def addProducts(src: File, products: Iterable[File]): Relations =
    new MRelationsNameHashing(srcProd ++ products.map(p => (src, p)), binaryDep,
      internalDependencies = internalDependencies, externalDependencies = externalDependencies,
      classes = classes, names = names, declaredClasses = declaredClasses)

  private[inc] def addClasses(src: File, classes: Iterable[(String, String)]): Relations =
    new MRelationsNameHashing(srcProd = srcProd, binaryDep,
      internalDependencies = internalDependencies, externalDependencies = externalDependencies,
      this.classes ++ classes.map(c => (src, c._1)), names = names, declaredClasses = declaredClasses)

  def addInternalSrcDeps(src: File, deps: Iterable[InternalDependency]) =
    new MRelationsNameHashing(srcProd, binaryDep, internalDependencies = internalDependencies ++ deps,
      externalDependencies = externalDependencies, classes, names, declaredClasses = declaredClasses)

  def addExternalDeps(src: File, deps: Iterable[ExternalDependency]) =
    new MRelationsNameHashing(srcProd, binaryDep, internalDependencies = internalDependencies,
      externalDependencies = externalDependencies ++ deps, classes, names, declaredClasses = declaredClasses)

  def addBinaryDeps(src: File, deps: Iterable[(File, String, Stamp)]) =
    new MRelationsNameHashing(srcProd, binaryDep + (src, deps.map(_._1)), internalDependencies = internalDependencies,
      externalDependencies = externalDependencies, classes, names, declaredClasses = declaredClasses)

  def addUsedName(src: File, name: String): Relations =
    new MRelationsNameHashing(srcProd, binaryDep, internalDependencies = internalDependencies,
      externalDependencies = externalDependencies, classes, names = names + (src, name),
      declaredClasses = declaredClasses)

  override private[inc] def addDeclaredClass(src: File, className: String): Relations =
    new MRelationsNameHashing(srcProd, binaryDep, internalDependencies = internalDependencies,
      externalDependencies = externalDependencies, classes, names = names,
      declaredClasses = declaredClasses + (src, className))

  /**
   * Names (fully qualified, at pickler phase) of classes defined in source
   * file `src`.
   */
  override private[inc] def declaredClassNames(src: File): Set[String] =
    declaredClasses.forward(src)

  override def inheritance: ClassDependencies =
    new ClassDependencies(internalDependencies.dependencies.getOrElse(DependencyByInheritance, Relation.empty), externalDependencies.dependencies.getOrElse(DependencyByInheritance, Relation.empty))
  override def memberRef: ClassDependencies =
    new ClassDependencies(internalDependencies.dependencies.getOrElse(DependencyByMemberRef, Relation.empty), externalDependencies.dependencies.getOrElse(DependencyByMemberRef, Relation.empty))

  def ++(o: Relations): Relations = {
    if (!o.nameHashing)
      throw new UnsupportedOperationException("The `++` operation is not supported for relations " +
        "with different values of `nameHashing` flag.")
    new MRelationsNameHashing(srcProd ++ o.srcProd, binaryDep ++ o.binaryDep,
      internalDependencies = internalDependencies ++ o.internalDependencies, externalDependencies = externalDependencies ++ o.externalDependencies,
      classes ++ o.classes, names = names ++ o.names, declaredClasses = declaredClasses ++ o.declaredClasses)
  }
  def --(sources: Iterable[File]) = {
    val classesInSources = sources.flatMap(declaredClassNames)
    new MRelationsNameHashing(srcProd -- sources, binaryDep -- sources,
      internalDependencies = internalDependencies -- classesInSources,
      externalDependencies = externalDependencies -- classesInSources, classes -- sources,
      names = names -- sources, declaredClasses = declaredClasses -- sources)
  }

  override def equals(other: Any) = other match {
    case o: MRelationsNameHashing =>
      srcProd == o.srcProd && binaryDep == o.binaryDep && memberRef == o.memberRef &&
        inheritance == o.inheritance && classes == o.classes && declaredClasses == o.declaredClasses
    case _ => false
  }

  override def hashCode = (srcProd :: binaryDep :: memberRef :: inheritance :: classes :: Nil).hashCode

  override def toString = (
    """
	  |Relations (with name hashing enabled):
	  |  products: %s
	  |  bin deps: %s
	  |  class deps: %s
	  |  ext deps: %s
	  |  class names: %s
	  |  used names: %s
    |  declared classes: %s
	  """.trim.stripMargin.format(List(srcProd, binaryDep, internalClassDep, externalClassDep, classes, names, declaredClasses) map relation_s: _*)
  )

}
