# ONLYOFFICE Docs licence: brief for legal review

> For the product owner's lawyer. Prepared 2026-11-14 (Phase 3 E02, overview §10 Q5c). It sets out facts, the licence texts and the questions we need answered before the first on-premises delivery and before a SaaS launch. It contains no legal conclusions.

## What the product does with ONLYOFFICE
- **The product:** memo, a memo and approval system for banks, offered as a hosted service (SaaS) and for installation in a customer's own data centre (on-premises).
- **The part we use:** ONLYOFFICE Docs Community Edition (the Document Server), so people can edit Word and Excel documents in the browser and convert documents to PDF.
- **How we run it:** the vendor's **unmodified** container image from Docker Hub, as a separate service. We do not change its code, its logo or its branding, and we do not rebrand it.
- **How we configure it:** environment variables, a configuration file, and the settings our application sends when it opens the editor. Among those settings we switch off plugins, macros and the AI features.
- **How our code talks to it:** over HTTP only (the editor's public API, callbacks when a document is saved, and the conversion API). The browser loads the editor's script from the Document Server, under our web address.
- **Versions:** 9.4.0.1 today; we move to 10.0.0.1 in this phase. The licence terms changed between these versions (below).
- **SaaS:** we operate the container; customers' users use the editor over the network.
- **On-premises:** we ship configuration files (Docker Compose, Helm) that make the customer's servers download the vendor image. An offline bundle that contains the image itself is possible for customers without internet access.

## The licence texts
**(a) 9.4.0.1 (currently pinned): source file headers** (`ONLYOFFICE/server` `DocService/sources/server.js` @v9.4.0.1):
> "In accordance with Section 7(a) of the GNU AGPL its Section 15 shall be amended to the effect that Ascensio System SIA expressly excludes the warranty of non-infringement of any third-party rights."
> "The interactive user interfaces in modified source and object code versions of the Program must display Appropriate Legal Notices, as required under Section 5 of the GNU AGPL version 3."
> "**Pursuant to Section 7(b) of the License you must retain the original Product logo when distributing the program.** Pursuant to Section 7(e) we decline to grant you any rights under trademark law for use of our trademarks."
> "All the Product's GUI elements, including illustrations and icon sets, as well as technical writing content are licensed under the terms of the Creative Commons Attribution-ShareAlike 4.0 International."

**(b) 10.0.0.1 and master: restructured.** The `LICENSE` file in ONLYOFFICE/DocumentServer was changed in commit `a07795c4` (2026-05-06, "Update LICENSE with restructured AGPLv3 additional terms"). Its preamble:
> "This Program is licensed under the GNU Affero General Public License v3.0 (AGPLv3), as supplemented by the terms set forth below. These terms form an integral part of this License and shall be included in all copies and distributions of the Program. ... **Failure to comply with these terms constitutes a violation of the License and may result in termination of rights under Section 8 of the License.**"
Additional Terms:
> "1. Retention of Notices and Attribution. Pursuant to Sections 4, 5, and 7(b) of the License, you must retain all applicable copyright notices, license notices, warranty disclaimers, and attribution or origin notices included in the Program."
> "2. Modification Notice Requirement. Modified versions of the Program must carry prominent notices stating that they have been modified, including the date (or dates) of such modification, and must clearly indicate that they are based on the original ONLYOFFICE software developed by Ascensio System SIA."
> "3. Appropriate Legal Notices in User Interfaces. Where the Program, or any modified version thereof, includes interactive user interfaces, the required notices shall be displayed through a clearly accessible and prominently visible user interface feature ... Such notices must enable users to: (i) identify ONLYOFFICE as the original developer; (ii) understand that the version in use may be a modified version; and (iii) access the applicable license information."
> "4. No Trademark License. In accordance with Section 7(e) this License does not grant any rights to use the trademarks, service marks, trade names, logos, or branding of the Licensor. Any use of such trademarks is governed by the Licensor's Trademark Policy, available at: https://www.onlyoffice.com/trademark-policy"
> "5. Non-Code Content Licensing. Certain non-code elements ... including ... illustrations, icon sets, and technical writing or documentation content, are licensed under ... CC BY-SA 4.0."
The 10.0.0.1 source headers carry `SPDX-License-Identifier: AGPL-3.0-only` and "No trademark rights are granted under this License". **The explicit "retain the original Product logo" sentence is gone in 10.0.**

**(c) AGPL v3 text (the relevant sections, from the same LICENSE file):**
- §13: "if you **modify** the Program, your modified version must prominently offer all users interacting with it remotely through a computer network ... an opportunity to receive the Corresponding Source of your version ..." (The trigger is modification.)
- §6: conveying object code requires conveying the Corresponding Source in one of the listed ways. For example §6(d): "offering access from a designated place ... and offer equivalent access to the Corresponding Source in the same way ... the Corresponding Source may be on a different server (operated by you or a third party) ... provided you maintain clear directions next to the object code ... you remain obligated to ensure that it is available for as long as needed".
- §7 permits only listed additional terms ((a)–(f), for example "(b) Requiring preservation of specified reasonable legal notices or author attributions", "(e) Declining to grant rights under trademark law"). "All other non-permissive additional terms are considered 'further restrictions' ... If the Program as you received it ... contains a notice stating that it is governed by this License along with a term that is a further restriction, **you may remove that term**."

**(d) Public dispute (context, no ruling):** after the Euro-Office fork (Nextcloud/IONOS) removed ONLYOFFICE branding, ONLYOFFICE called it a licence violation. The FSF reportedly said the logo requirement is a further restriction that recipients may remove. Commentators say the question (does a logo count as a §7(b) "legal notice or author attribution"?) may only be settled in court. Sources: https://linuxiac.com/fsf-says-onlyoffice-cannot-use-agpl-to-restrict-forks/ · https://ie-forum.nl/artikelen/the-logo-trap-onlyoffice-s-agplv3-gambit-and-what-it-means-for-free-and-open-source-compliance · https://www.onlyoffice.com/blog/2026/03/interview-with-lev-bannov-ceo-at-onlyoffice-on-the-euro-office-situation · https://heise.de/-11241334 · https://lwn.net/Articles/1067771/


## Questions
1. With an unmodified Document Server, is AGPL §13 triggered at all for SaaS? Does configuration (local.json, environment variables, editor `customization` flags that hide plugins/AI/macros) count as "modification"?
2. On-prem: is shipping Compose/Helm files that make the customer pull the image from Docker Hub "conveying"? If we ship the image in an offline bundle, what source-offer form satisfies §6 (a pointer to the GitHub tag per §6(d), or a written offer)? Does this cover the third-party and Ubuntu base-image packages inside the image (GPL components), and for how long must the source stay available?
3. Which additional terms apply to us: the 9.4 header terms ("retain the original Product logo", §15 warranty amendment) or the 10.0 LICENSE terms? Can 9.4 and 10.x terms differ for the same customer over upgrades?
4. Additional Term 3 (notices in the UI): is the editor's own About screen enough when it is embedded in our iframe? Must our product show anything ("powered by ONLYOFFICE", a licence link)?
5. Trademark policy: may we name "ONLYOFFICE" in our docs, UI, marketing and runbooks? Under what conditions?
6. The "termination under Section 8" clause and the enforceability of the logo/notice terms (§7 "further restrictions"): what is the risk for us, given that we comply in full anyway?
7. CC BY-SA 4.0 on GUI elements/icons: any obligations when shown inside our product?
8. Is Enterprise Edition (commercial licence) needed for any customer scenario (HA, white-label, support)? What are the contractual terms?

