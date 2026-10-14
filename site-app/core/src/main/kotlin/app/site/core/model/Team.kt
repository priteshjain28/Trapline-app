package app.site.core.model

import java.time.Instant

/**
 * People on a site (S36). Mirrors org_members, project_members and invites. Company owners and admins act as
 * managers on every site, the same rule as the server's app.project_role().
 */
data class Member(
    val userId: String,
    val name: String,
    val phone: String,
    /** Their role on this site; owners and admins count as managers. */
    val role: ProjectRole,
    /** Set for company owners and admins: their role comes from the company, not this site. */
    val orgRole: OrgRole? = null,
) {
    val fromCompany: Boolean get() = orgRole == OrgRole.OWNER || orgRole == OrgRole.ADMIN
}

data class PendingInvite(val id: String, val phone: String, val role: ProjectRole?, val createdAt: Instant)

data class Team(
    val members: List<Member>,
    val invites: List<PendingInvite>,
    /** The signed-in user may invite, change roles and remove people here. */
    val canManage: Boolean,
)
