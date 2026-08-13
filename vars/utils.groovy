def updateGitHubStatus(String context, String status, String description) {
    githubNotify(
        context: context,
        status: status,          // PENDING | SUCCESS | FAILURE | ERROR
        description: description,
        credentialsId: 'github-token'
    )
}