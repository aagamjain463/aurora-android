# App Store Guideline 4.10: Aurora monetization audit

Checked 2026-11-04 against [Apple's current App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/#monetizing-built-in-capabilities). Engineering risk assessment, not Apple approval. Product behavior, Free limits and pricing have not changed.

Section 4.10 prohibits monetizing built-in capabilities and explicitly names Screen Time APIs. A free allowance does not establish that charging for additional use is allowed. StoreKit billing and cross-platform account recognition do not resolve this separate rule.

Current iOS limits in `Aurora/Shared/Domain/Plan.swift`: five blocking targets, three Intent apps, one active Goal, 120 committed minutes per day. The Plus comparison removes these limits. Family Controls, Managed Settings and Device Activity implement shielding and strict sessions.

| Proposed paid benefit | Assessment | Before submission |
| --- | --- | --- |
| More than five blocking targets | High risk: payment increases how many apps/sites Screen Time shields. | Ask Apple explicitly whether the limit monetizes Screen Time. |
| More than 120 committed/strict minutes daily | High risk: payment increases duration of Screen Time enforcement. | Show strict, cooldown and friction behavior and resolve with Apple. |
| More Intent apps/presets or active Goals | Unclear to high risk when payment expands Screen Time shielding. | Separate independent workflow value from additional API use. |
| Stronger blocking, Screen Time permission, shielding/scheduling/report access | High risk when sold as access to built-in capability. | Do not add these as paid benefits pending clarification. |
| Cross-platform Plus | Purchasing architecture does not validate underlying paid benefits. | Audit each iOS benefit and retain native purchasing. |

Potential independently valuable proprietary services include original coaching content/courses, planning/reflection workflows, genuinely proprietary analysis of appropriate consented inputs, and a real cloud synchronization/backup service. These are candidates, not pre-approved exceptions or promises that Aurora already supplies them. Their value must exist beyond permission to use Apple's APIs. Renaming shielding as a proprietary engine does not solve 4.10. Paid analysis must not simply sell access to Screen Time reports; data services need separate privacy and consent review.

Before submission:

1. Show Apple the exact five-target and 120-minute restrictions, unlimited Plus comparison and purchase screens. Ask about each under 4.10.
2. Identify what Aurora implements itself versus Family Controls, Managed Settings and Device Activity. Ask which independent paid benefits Apple would accept in the bundle.
3. Show Intent/Goal limits and strict/cooldown/friction flows, including duration and additional apps affected.
4. Explain verified account Plus across platforms, native billing on each, and no redirection to buy elsewhere. Ask about the proposed presentation under 3.1.3(b).
5. Explain sandbox-only review access and its production isolation. Prepare accurate reviewer instructions for any server-dependent quota flow.

Keep Apple's response and the precise build/screens it covers. A competitor's approval or a generic answer does not settle Aurora's design. Treat unresolved 4.10 questions as a submission blocker. Obtain the user's approval before changing Free/Plus restrictions, products or pricing.

Family Sharing remains OFF for both initial products. Do not enable it experimentally: Apple says enabling it cannot be undone. Real App Store Connect configuration and sandbox behavior remain later checks. Local tests cannot establish compliance, provisioning or App Review acceptance.
