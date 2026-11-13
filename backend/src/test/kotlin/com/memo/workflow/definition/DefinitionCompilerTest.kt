package com.memo.workflow.definition

import com.memo.workflow.AttributeType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** Schema upgrades and fragment inlining (ADR-0038). */
class DefinitionCompilerTest {
    private val alice = Assignee(AssigneeKind.PERSON, UUID(0, 1))
    private val bob = Assignee(AssigneeKind.PERSON, UUID(0, 2))

    @Test
    fun `the E01 schema-1 body reads as one route step`() {
        val body =
            JsonMapper.builder().build().readTree(
                """{"kind":"sequential-approval","route":"initiator-defined","resubmit":"restart"}""",
            )
        val definition = DefinitionCompiler.read(body, 1)
        assertThat(definition.start).isEqualTo("route")
        assertThat(definition.steps.single()).isEqualTo(StepDef(id = "route", type = StepType.ROUTE, next = Definition.END))
    }

    @Test
    fun `fragments are copied in with prefixed ids, entered at their start and left at the call's next`() {
        val legal =
            Definition(
                attributes = listOf(Attribute("classificationRank", AttributeType.INT)),
                start = "check",
                steps =
                    listOf(
                        StepDef(
                            id = "check",
                            type = StepType.BRANCH,
                            branches = listOf(Branch("item.classificationRank > 2", "counsel")),
                            otherwise = Definition.END,
                        ),
                        StepDef(id = "counsel", type = StepType.APPROVE, assignee = bob, next = Definition.END),
                    ),
            )
        val parent =
            Definition(
                start = "first",
                steps =
                    listOf(
                        StepDef(id = "first", type = StepType.SUBWORKFLOW, definition = "legal", next = "second"),
                        StepDef(id = "second", type = StepType.SUBWORKFLOW, definition = "legal", next = "sign-off"),
                        StepDef(id = "sign-off", type = StepType.APPROVE, assignee = alice, next = Definition.END),
                    ),
            )
        val compiled = DefinitionCompiler.inline(parent) { if (it == "legal") legal else null }

        assertThat(compiled.start).isEqualTo("first/check")
        assertThat(
            compiled.steps.map { it.id },
        ).containsExactly("sign-off", "first/check", "first/counsel", "second/check", "second/counsel")
        assertThat(
            compiled
                .step("first/check")
                .branches
                .single()
                .next,
        ).isEqualTo("first/counsel")
        // The first call's end continues at the second call, which is itself replaced by its fragment's start.
        assertThat(compiled.step("first/check").otherwise).isEqualTo("second/check")
        assertThat(compiled.step("first/counsel").next).isEqualTo("second/check")
        assertThat(compiled.step("second/counsel").next).isEqualTo("sign-off")
        assertThat(compiled.attributes).containsExactly(Attribute("classificationRank", AttributeType.INT))
        assertThat(compiled.steps).noneMatch { it.type == StepType.SUBWORKFLOW }
    }

    @Test
    fun `a definition without sub-workflows is returned as it is`() {
        val plain =
            Definition(start = "a", steps = listOf(StepDef(id = "a", type = StepType.APPROVE, assignee = alice, next = Definition.END)))
        assertThat(DefinitionCompiler.inline(plain) { null }).isSameAs(plain)
    }
}
