package dev.openrune.server

/**
 * Annotation to provide a description for a query parameter
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class ParamDescription(val value: String)






