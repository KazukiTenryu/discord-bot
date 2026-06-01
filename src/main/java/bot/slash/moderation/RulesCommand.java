package bot.slash.moderation;

import java.awt.*;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

import bot.slash.SlashCommand;

public class RulesCommand extends SlashCommand {

    /** Deep velvet / burgundy used across every panel for a cohesive look. */
    private static final Color VELVET = new Color(0x6B0F2B);

    public RulesCommand() {
        super("rules", "View the server rules");
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        event.replyEmbeds(banner(), conduct(), consent(), privacyAndEnforcement())
                .queue();
    }

    private MessageEmbed banner() {
        EmbedBuilder b = new EmbedBuilder();
        b.setColor(VELVET);
        b.setTitle("🌹  The Velvet Room");
        b.setDescription("""
            ```
            ╔═══════════════════════════╗
            ║     THE  VELVET  ROOM     ║
            ║      RULES OF ENTRY       ║
            ╚═══════════════════════════╝
            ```
            Welcome, guest. This is an **18+ adult sanctuary** — a place to \
            unwind, connect, and be yourself. Comfort and safety here are \
            **not negotiable**.

            Read every rule before you take part. *Entering without \
            understanding is still entering.*
            ━━━━━━━━━━━━━━━━━━━━━━━━━━━""");
        return b.build();
    }

    private MessageEmbed conduct() {
        EmbedBuilder b = new EmbedBuilder();
        b.setColor(VELVET);
        b.setTitle("📜  I — General Conduct");
        b.setDescription("""
            **1 · Treat everyone with respect**
            Basic decency is the floor, not the ceiling. Kindness costs nothing.

            **2 · Keep the peace**
            Personal conflicts go to DMs or get dropped. This is not reality TV.

            **3 · No spam or flooding**
            Walls of text, emoji storms, copypasta, and mention-spam get removed.

            **4 · Stay roughly on topic**
            Conversation can wander; deliberate derailing and chaos-dumping cannot.

            **5 · Respect the staff**
            Mods volunteer their time. Follow their directions — argue appeals in \
            DMs, never in chat.

            **6 · No loophole lawyering**
            "Technically allowed" is still breaking the rules. Use common sense.

            **7 · English in main channels**
            So everyone stays included and safe, unless a channel says otherwise.""");
        return b.build();
    }

    private MessageEmbed consent() {
        EmbedBuilder b = new EmbedBuilder();
        b.setColor(VELVET);
        b.setTitle("🩷  II — Consent, Safety & Respect");
        b.setDescription("""
            *The heart of the Velvet Room. These rules exist to keep everyone — \
            especially women and anyone who's ever felt unsafe online — protected. \
            Breaking them is the fastest way out the door.*

            **1 · Consent is mandatory, always**
            No unwanted advances, pet names, flirting, or roleplay. "No," silence, \
            or "not interested" all mean **stop** — immediately and permanently.

            **2 · Ask before you DM**
            Do not slide into anyone's DMs uninvited. No unsolicited messages, \
            friend requests, or following someone from channel to channel.

            **3 · Never send unsolicited explicit content**
            No NSFW images, links, or descriptions to anyone who didn't clearly \
            ask for them. This is an instant-removal offense.

            **4 · Zero tolerance for harassment**
            Misogyny, objectification, predatory behavior, sexualizing someone \
            without consent, or pressuring for photos / voice / video / personal \
            info is a **ban**.

            **5 · Respect boundaries & identity**
            Honor stated limits, pronouns, and comfort levels — no complaints, \
            no "debating" someone's boundaries.

            **6 · Look out for each other**
            If someone's being made uncomfortable, back off when asked and let \
            staff step in.

            **7 · Report it — you'll be believed**
            Cross a line and you're gone. See a line crossed and you ping a mod \
            or open a ticket. Retaliating against someone who reports is a ban.""");
        return b.build();
    }

    private MessageEmbed privacyAndEnforcement() {
        EmbedBuilder b = new EmbedBuilder();
        b.setColor(VELVET);
        b.setTitle("🔒  III — Privacy, Content & Enforcement");
        b.setDescription("""
            **1 · Privacy is sacred**
            No doxxing, no sharing anyone's personal info, no leaking DMs or \
            screenshots to start drama.

            **2 · 18+ only**
            Everyone here is an adult. Minors are removed and reported — no \
            exceptions, no warnings.

            **3 · NSFW stays in its channels**
            Keep mature content where it belongs, and always behind consent.

            **4 · Nothing illegal, ever**
            Child content, real threats, and non-consensual material mean an \
            instant permanent ban and a report to the proper authorities.

            **5 · No impersonation or scams**
            Don't pose as staff or other members. No phishing, advertising, or \
            self-promo without permission.

            **6 · Enforcement ladder**
            Depending on severity: `warning → mute → kick → ban`. Staff use their \
            judgment and their decisions are final. Appeal calmly in DMs.
            ━━━━━━━━━━━━━━━━━━━━━━━━━━━""");
        b.setFooter("Entering without understanding is still entering.  ·  Mod decisions are final.");
        return b.build();
    }
}
