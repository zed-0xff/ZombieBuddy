package me.zed_0xff.zombie_buddy.frontend;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import javax.swing.JLabel;
import me.zed_0xff.zombie_buddy.JarBatchApprovalProtocol.Entry.ZBSignature;
import me.zed_0xff.zombie_buddy.SteamWorkshop.SteamID64;
import org.junit.jupiter.api.Test;

class SwingApprovalMainTest {
    private static final SteamID64 AUTHOR = new SteamID64(76561197974968019L);
    private static final String PROFILE =
            "https://steamcommunity.com/profiles/" + AUTHOR + "/myworkshopfiles/?appid=108600";

    @Test
    void officialNameOverridesRequestLabelAndRetainsSteamIdentity() {
        JLabel link = SwingApprovalMain.authorLink(
                new ZBSignature(true, AUTHOR, "Local name"), Map.of(AUTHOR, "Official < & name"));
        assertEquals("<html><a href=\"" + PROFILE + "\">Official &lt; &amp; name</a></html>", link.getText());
        assertEquals(AUTHOR.toString(), link.getToolTipText());
        assertTrue(link.getMouseListeners().length > 0);
    }

    @Test
    void absentOfficialAuthorUsesEscapedRequestLabel() {
        JLabel link = SwingApprovalMain.authorLink(
                new ZBSignature(true, AUTHOR, "Local < & name"), Map.of());
        assertEquals("<html><a href=\"" + PROFILE + "\">Local &lt; &amp; name</a></html>", link.getText());
        assertEquals(AUTHOR.toString(), link.getToolTipText());
    }

    @Test
    void unavailableOrBlankOfficialNameUsesRequestLabel() {
        ZBSignature signature = new ZBSignature(true, AUTHOR, "Local name");
        assertEquals(SwingApprovalMain.authorLink(signature, null).getText(),
                SwingApprovalMain.authorLink(signature, Map.of(AUTHOR, " ")).getText());
        assertTrue(SwingApprovalMain.authorLink(signature, null).getText().contains(">Local name</a>"));
    }

    @Test
    void missingLabelsFallBackToSteamId() {
        JLabel link = SwingApprovalMain.authorLink(new ZBSignature(true, AUTHOR, ""), Map.of());
        assertEquals("<html><a href=\"" + PROFILE + "\">" + AUTHOR + "</a></html>", link.getText());
        assertNull(link.getToolTipText());
    }
}
