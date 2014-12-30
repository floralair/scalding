package com.twitter.scalding.macros.impl

import scala.language.experimental.macros
import scala.reflect.macros.Context
import scala.reflect.runtime.universe._

import com.twitter.scalding._
import com.twitter.bijection.macros.IsCaseClass
import com.twitter.bijection.macros.impl.IsCaseClassImpl
/**
 * This class contains the core macro implementations. This is in a separate module to allow it to be in
 * a separate compilation unit, which makes it easier to provide helper methods interfacing with macros.
 */
object FieldsProviderImpl {
  def toFieldsImpl[T](c: Context)(proof: c.Expr[IsCaseClass[T]])(implicit T: c.WeakTypeTag[T]): c.Expr[cascading.tuple.Fields] =
    toFieldsNoProofImpl(c, true)(T)

  def toIndexedFieldsImpl[T](c: Context)(proof: c.Expr[IsCaseClass[T]])(implicit T: c.WeakTypeTag[T]): c.Expr[cascading.tuple.Fields] =
    toFieldsNoProofImpl(c, false)(T)

  def toFieldsNoProofImpl[T](c: Context, namedFields: Boolean)(implicit T: c.WeakTypeTag[T]): c.Expr[cascading.tuple.Fields] = {
    import c.universe._

    def matchField(fieldType: Type, outerName: String, fieldName: String, isOption: Boolean): List[(Tree, String)] = {
      val returningType = if (isOption) q"""classOf[java.lang.Object]""" else q"""classOf[$fieldType]"""
      val simpleRet = List((returningType, s"$outerName$fieldName"))
      fieldType match {
        case tpe if tpe =:= typeOf[String] => simpleRet
        case tpe if tpe =:= typeOf[Boolean] => simpleRet
        case tpe if tpe =:= typeOf[Short] => simpleRet
        case tpe if tpe =:= typeOf[Int] => simpleRet
        case tpe if tpe =:= typeOf[Long] => simpleRet
        case tpe if tpe =:= typeOf[Float] => simpleRet
        case tpe if tpe =:= typeOf[Double] => simpleRet
        case tpe if tpe.erasure =:= typeOf[Option[Any]] && isOption == true => c.abort(c.enclosingPosition, s"Case class ${T} has nested options, not supported currently.")
        case tpe if tpe.erasure =:= typeOf[Option[Any]] =>
          val innerType = tpe.asInstanceOf[TypeRefApi].args.head
          matchField(innerType, outerName, fieldName, true)
        case tpe if IsCaseClassImpl.isCaseClassType(c)(tpe) => expandMethod(tpe, s"$outerName$fieldName.", isOption)
        case _ => c.abort(c.enclosingPosition, s"Case class ${T} is not pure primitives or nested case classes")
      }
    }

    def expandMethod(outerTpe: Type, outerName: String, isOption: Boolean): List[(Tree, String)] = {
      outerTpe
        .declarations
        .collect { case m: MethodSymbol if m.isCaseAccessor => m }
        .flatMap { accessorMethod =>
          val fieldName = accessorMethod.name.toTermName.toString
          val fieldType = accessorMethod.returnType
          matchField(fieldType, outerName, fieldName, isOption)
        }.toList
    }

    val expanded = expandMethod(T.tpe, "", false)
    if (expanded.isEmpty) c.abort(c.enclosingPosition, s"Case class ${T} has no primitive types we were able to extract")

    val typeTrees = expanded.map(_._1)

    val res = if (namedFields) {
      val fieldNames = expanded.map(_._2)
      q"""
      new _root_.cascading.tuple.Fields(_root_.scala.Array.apply[java.lang.Comparable[_]](..$fieldNames), scala.Array.apply[java.lang.reflect.Type](..$typeTrees)) with _root_.com.twitter.bijection.macros.MacroGenerated
      """
    } else {
      val indices = typeTrees.zipWithIndex.map(_._2)
      q"""
      new _root_.cascading.tuple.Fields(_root_.scala.Array.apply[java.lang.Comparable[_]](..$indices), scala.Array.apply[java.lang.reflect.Type](..$typeTrees)) with _root_.com.twitter.bijection.macros.MacroGenerated
      """
    }
    c.Expr[cascading.tuple.Fields](res)
  }

}
