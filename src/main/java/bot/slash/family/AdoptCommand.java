package bot.slash.family;

public class AdoptCommand extends AbstractProposalCommand {
    public AdoptCommand(RelationshipService service) {
        super(
                "adopt",
                "Adopt someone into your family",
                "the person you want to adopt",
                RelationshipService.PARENT,
                service);
    }

    @Override
    protected String proposalText(String proposerId, String targetId) {
        return "🍼 <@" + proposerId + "> wants to **adopt** <@" + targetId + ">!\n\n"
                + "<@" + targetId + ">, will you join the family?";
    }
}
