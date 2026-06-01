package bot.slash.family;

public class ClaimCommand extends AbstractProposalCommand {
    public ClaimCommand(RelationshipService service) {
        super(
                "claim",
                "Claim someone as yours (with their consent)",
                "the person you want to claim",
                RelationshipService.OWNER,
                service);
    }

    @Override
    protected String proposalText(String proposerId, String targetId) {
        return "🔗 <@" + proposerId + "> wants to **claim** <@" + targetId + "> as theirs.\n\n" + "<@" + targetId
                + ">, do you consent? Nothing happens unless you say yes.";
    }
}
