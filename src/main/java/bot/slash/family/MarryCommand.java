package bot.slash.family;

public class MarryCommand extends AbstractProposalCommand {
    public MarryCommand(RelationshipService service) {
        super(
                "marry",
                "Propose marriage to someone",
                "the person you want to marry",
                RelationshipService.MARRIAGE,
                service);
    }

    @Override
    protected String proposalText(String proposerId, String targetId) {
        return "💍 <@" + proposerId + "> wants to **marry** <@" + targetId + ">!\n\n"
                + "<@" + targetId + ">, do you accept?";
    }
}
