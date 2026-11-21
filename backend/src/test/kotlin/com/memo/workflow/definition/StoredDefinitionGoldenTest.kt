package com.memo.workflow.definition

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

/**
 * Definitions stored before ADR-0068 (Phase 2 and 3 keys only) still validate and print back byte for byte in the
 * canonical order: the engine additions are optional keys, so a stored body and its compiled form never change.
 */
class StoredDefinitionGoldenTest {
    private val json = JsonMapper.builder().build()

    /** Every Phase 2/3 key of a definition, in the canonical order [DefinitionJson.print] writes. */
    private val golden =
        """
        {"schemaVersion":2,"attributes":[{"name":"classificationRank","type":"int"}],"resubmit":"resume","start":"desk","steps":[
          {"id":"desk","type":"review","name":"Desk","assignee":{"kind":"queue","queue":"credit-desk"},
           "fallback":{"kind":"person","userId":"00000000-0000-0000-0000-00000000000a"},"forward":{"within":"unit"},
           "actions":["RECOMMEND","RETURN","FORWARD"],"remarks":["RETURN"],
           "sla":{"due":"PT4H","workingTime":true,"remind":{"before":"PT1H","after":["PT1H","P1D"]},
                  "escalate":{"after":"P1D","action":"reassign","to":{"kind":"supervisor","levels":1}}},"next":"size"},
          {"id":"size","type":"branch","name":"Size","branches":[{"when":"item.classificationRank >= 3","next":"board"}],
           "otherwise":"head"},
          {"id":"head","type":"approve","assignee":{"kind":"unitHead","unit":"initiator","up":1},
           "sla":{"due":"P2D","escalate":{"after":"P1D","action":"notify"}},"next":"route"},
          {"id":"route","type":"route","next":"doa"},
          {"id":"doa","type":"doa","next":"board"},
          {"id":"board","type":"parallel","assignees":[{"kind":"position","positionId":"00000000-0000-0000-0000-0000000000b0"},
           {"kind":"role","unit":"initiator","roleId":"00000000-0000-0000-0000-0000000000c0"}],"mode":"any","next":"forum"},
          {"id":"forum","type":"escalate-to-forum","forum":"board","wait":true,"next":"tell"},
          {"id":"tell","type":"notify","recipients":[{"kind":"initiator"}],"message":"approvedByBoard","next":"end"}]}
        """

    @Test
    fun `a definition stored before the engine additions validates and prints back unchanged`() {
        val stored = json.readTree(golden)
        val result = DefinitionValidator(null) { null }.validate(stored)
        assertThat(result.problems).isEmpty()
        val definition = checkNotNull(result.definition)
        assertThat(definition.subject).isNull()
        assertThat(definition.steps.mapNotNull { it.sla }.none { it.fromSubject }).isTrue()
        assertThat(DefinitionJson.print(definition).toString()).isEqualTo(stored.toString())
    }

    @Test
    fun `the built-in sequential approval is unchanged`() {
        val printed = DefinitionJson.print(DefinitionCompiler.SEQUENTIAL).toString()
        assertThat(printed).isEqualTo("""{"schemaVersion":2,"start":"route","steps":[{"id":"route","type":"route","next":"end"}]}""")
    }
}
