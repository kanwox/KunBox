package com.kunk.singbox.repository

import com.google.gson.Gson
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.DnsConfig
import com.kunk.singbox.model.DnsRule
import com.kunk.singbox.model.DnsServer
import com.kunk.singbox.model.IpVersionMode
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.RootAppRoutingAssignment
import com.kunk.singbox.model.RootAppRoutingCanonical
import com.kunk.singbox.model.RootAppRoutingPlan
import com.kunk.singbox.model.RootAppRoutingPlanCompiler
import com.kunk.singbox.model.RootRoutingArtifactValidator
import com.kunk.singbox.model.RouteConfig
import com.kunk.singbox.model.SingBoxConfig
import com.kunk.singbox.model.TrafficCaptureMode
import com.kunk.singbox.model.TunStack
import com.kunk.singbox.repository.config.InboundBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("CognitiveComplexMethod")
class RootLaneRuntimeBindingTest {
    @Test
    fun targetsFamiliesAndFakeDnsSurviveSanitizingAndRootProcessRoundTrip() {
        listOf(IpVersionMode.IPV4_ONLY, IpVersionMode.IPV6_ONLY, IpVersionMode.DUAL_STACK).forEach { family ->
            listOf(false, true).forEach { fakeDns ->
                val (plan, config) = fixture(family, fakeDns)
                val bindings = ConfigRepository.buildRootLaneRuntimeBindings(plan)
                bindings.forEach { binding ->
                    val lane = binding.lane
                    val blocked = lane.targetKind == "BLOCK"
                    assertEquals(if (blocked) "reject" else "route", binding.routeRule.action)
                    assertEquals(if (blocked) null else lane.outboundTag, binding.routeRule.outbound)
                    assertEquals(if (blocked) "predefined" else "route", binding.dnsRule.action)
                    assertEquals(
                        if (fakeDns && lane.targetKind == "OUTBOUND") listOf("A", "AAAA") else null,
                        binding.dnsRule.queryType
                    )
                    assertEquals(if (family == IpVersionMode.DUAL_STACK) 4 else 2, binding.inboundTags.size)
                    assertEquals(binding.inboundTags, binding.dnsRule.inbound)
                    assertEquals(binding.inboundTags, binding.routeRule.inbound)
                }
                val dns = requireNotNull(config.dns)
                val sanitized = config.copy(dns = dns.copy(rules = ConfigRepository.sanitizeDnsRulesForRuntime(
                    dns.rules.orEmpty(), dns.servers.orEmpty().mapNotNull(DnsServer::tag).toSet()
                )))
                assertEquals(dns.rules, sanitized.dns?.rules)
                ConfigRepository.requireValidRootApplicationRoutes(sanitized, plan, bindings)
                val serialized = Gson().toJson(sanitized)
                assertFalse(serialized.contains("\"fakeip\":"))
                val bound = plan.copy(configFileSha256 = RootAppRoutingCanonical.sha256(serialized.toByteArray()))
                val restoredPlan = RootRoutingArtifactValidator.requireBoundPlanJson(Gson().toJson(bound))
                val restoredConfig = Gson().fromJson(serialized, SingBoxConfig::class.java)
                assertEquals(fakeDns, restoredPlan.fakeDnsEnabled)
                ConfigRepository.requireValidRootApplicationRoutes(restoredConfig, restoredPlan)
            }
        }
    }

    @Test
    fun rejectsMissingDuplicateAlteredDnsAndWrongDetour() {
        val (plan, config) = fixture()
        val dns = requireNotNull(config.dns)
        val rules = dns.rules.orEmpty()
        val proxy = rules.first { it.queryType != null }
        val corruptedRules = listOf(
            rules - proxy,
            rules + proxy,
            rules.map { if (it == proxy) it.copy(queryType = null) else it },
            rules.map { if (it == proxy) it.copy(inbound = it.inbound.orEmpty().dropLast(1)) else it },
            rules.map { if (it == proxy) it.copy(sourcePort = listOf(53)) else it }
        )
        corruptedRules.forEach { corrupted ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                ConfigRepository.requireValidRootApplicationRoutes(config.copy(dns = dns.copy(rules = corrupted)), plan)
            }
            assertTrue(error.message.orEmpty().contains("expected="))
            assertTrue(error.message.orEmpty().contains("actual="))
        }
        val wrongDetour = dns.servers.orEmpty().map { if (it.tag == proxy.server) it.copy(detour = "direct") else it }
        assertThrows(IllegalArgumentException::class.java) {
            ConfigRepository.requireValidRootApplicationRoutes(config.copy(dns = dns.copy(servers = wrongDetour)), plan)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConfigRepository.requireValidRootApplicationRoutes(
                config.copy(dns = dns.copy(servers = dns.servers.orEmpty() + dns.servers.orEmpty().first())), plan
            )
        }
    }

    @Test
    fun rejectsWrongListenerPortAndForeignCompiledBinding() {
        val (plan, config) = fixture()
        val tag = plan.lanes.first().tcpInboundIpv4
        assertThrows(IllegalArgumentException::class.java) {
            ConfigRepository.requireValidRootApplicationRoutes(config.copy(inbounds = config.inbounds.orEmpty().map {
                if (it.tag == tag) it.copy(listenPort = 1) else it
            }), plan)
        }
        val bindings = ConfigRepository.buildRootLaneRuntimeBindings(plan)
        assertThrows(IllegalArgumentException::class.java) {
            ConfigRepository.requireValidRootApplicationRoutes(config, plan, bindings.dropLast(1))
        }
    }

    @Test
    fun fakeDnsFlagIsRequiredAndCoveredByDigest() {
        val (plan, _) = fixture()
        assertNotEquals(
            plan.staticPlanSha256,
            RootAppRoutingCanonical.staticPlanSha256(plan.copy(fakeDnsEnabled = false))
        )
        val json = Gson().toJsonTree(plan.copy(configFileSha256 = "0".repeat(64))).asJsonObject
        val absent = json.deepCopy().apply { remove("fakeDnsEnabled") }
        val changed = json.deepCopy().apply { addProperty("fakeDnsEnabled", false) }
        listOf(absent, changed).forEach {
            assertThrows(IllegalStateException::class.java) {
                RootRoutingArtifactValidator.requireBoundPlanJson(it.toString())
            }
        }
    }

    @Test
    fun legacySidecarRetainsDigestAndReadsModernFakeIpServer() {
        val (plan, config) = fixture()
        val legacy = plan.copy(schema = 1, configFileSha256 = "0".repeat(64)).let {
            it.copy(staticPlanSha256 = RootAppRoutingCanonical.staticPlanSha256(it))
        }
        val legacyJson = Gson().toJsonTree(legacy).asJsonObject.apply { remove("fakeDnsEnabled") }
        val restored = RootRoutingArtifactValidator.requireBoundPlanJson(legacyJson.toString())
        assertEquals(legacy.staticPlanSha256, RootAppRoutingCanonical.staticPlanSha256(restored))
        assertNull(config.dns?.fakeip)
        ConfigRepository.requireValidRootApplicationRoutes(config, restored)
    }

    @Test
    fun nestedOverrideCannotClaimLaneButOrdinaryDomainPriorityAndTunOverrideRemain() {
        val protected = "root-lane-000-udp-v4"
        val base = DnsConfig(servers = listOf(DnsServer(tag = "local", type = "local")))
        val nested = DnsRule(type = "logical", mode = "and", action = "route", server = "local",
            rules = listOf(DnsRule(inbound = listOf(protected))))
        assertThrows(IllegalStateException::class.java) {
            ConfigRepository.applyDnsOverride(base, DnsConfig(rules = listOf(nested)),
                protectedInboundTags = setOf(protected))
        }
        val user = DnsRule(domain = listOf("example.com"), server = "local")
        val merged = ConfigRepository.applyDnsOverride(base, DnsConfig(rules = listOf(user)),
            protectedInboundTags = setOf(protected))
        assertEquals(user.domain, merged.rules.orEmpty().first().domain)
        val replacement = DnsServer(tag = "local", type = "udp", server = "1.1.1.1")
        assertEquals(replacement, ConfigRepository.applyDnsOverride(base,
            DnsConfig(servers = listOf(replacement))).servers.orEmpty().single())
    }

    private fun fixture(
        family: IpVersionMode = IpVersionMode.DUAL_STACK,
        fakeDns: Boolean = true
    ): Pair<RootAppRoutingPlan, SingBoxConfig> {
        val settings = AppSettings(trafficCaptureMode = TrafficCaptureMode.ROOT_TRANSPARENT,
            ipVersionMode = family, fakeDnsEnabled = fakeDns)
        val plan = RootAppRoutingPlanCompiler.compile(settings, listOf(
            RootAppRoutingAssignment(listOf("app.direct"), "DIRECT", "direct", sourceLabel = "direct"),
            RootAppRoutingAssignment(listOf("app.block"), "BLOCK", routeAction = "reject", sourceLabel = "block"),
            RootAppRoutingAssignment(listOf("app.proxy"), "OUTBOUND", "PROXY", sourceLabel = "proxy")
        ), 10L)
        val bindings = ConfigRepository.buildRootLaneRuntimeBindings(plan)
        val servers = listOf(DnsServer(tag = "local", type = "local"),
            DnsServer(tag = ConfigRepository.buildDynamicDnsServerTag("PROXY"), type = "udp",
                server = "1.1.1.1", detour = "PROXY")) +
            if (fakeDns) listOf(ConfigRepository.buildFakeIpDnsServer(null)) else emptyList()
        return plan to SingBoxConfig(
            inbounds = InboundBuilder.build(settings, TunStack.SYSTEM, plan),
            outbounds = listOf(Outbound(type = "selector", tag = "PROXY", outbounds = listOf("node"), default = "node"),
                Outbound(type = "socks", tag = "node", server = "127.0.0.1", serverPort = 1080),
                Outbound(type = "direct", tag = "direct")),
            dns = DnsConfig(servers = servers, rules = bindings.map { it.dnsRule }),
            route = RouteConfig(rules = bindings.map { it.routeRule })
        )
    }
}
