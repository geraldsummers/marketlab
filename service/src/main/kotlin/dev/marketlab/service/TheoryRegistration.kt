package dev.marketlab.service

import dev.marketlab.persistence.TheoryRepository
import dev.marketlab.theories.AcademicTheoryRegistry
import dev.marketlab.theory.TheoryPlanHasher
import kotlinx.serialization.json.jsonObject
import javax.sql.DataSource

/**
 * Compiles and validates the built-in catalog before accepting requests, then
 * records each exact canonical plan under its immutable semantic version.
 * Re-running this function is safe; a changed plan under an existing version
 * is rejected by [TheoryRepository] rather than silently replacing history.
 */
fun registerAcademicTheories(dataSource: DataSource) {
    val repository = TheoryRepository(dataSource)
    AcademicTheoryRegistry.compileAll().forEach { plan ->
        val canonicalPlan = TheoryPlanHasher.canonicalJson(plan)
        val planObject = ServiceJson.parseToJsonElement(canonicalPlan).jsonObject
        val descriptorObject =
            requireNotNull(planObject["descriptor"]) {
                "Canonical theory plan omitted its descriptor"
            }.jsonObject
        repository.register(
            theoryId = plan.descriptor.id.value,
            version = plan.descriptor.version,
            planHash = TheoryPlanHasher.hash(plan).hex,
            descriptor = descriptorObject,
            plan = planObject,
        )
    }
}
