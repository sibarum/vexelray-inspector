package dev.sibarum.inspector;

import dev.sibarum.inspector.graphics.Graphics;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.StatusBar;

/**
 * What the inspected application draws with: one line at the right of the status bar ({@code GPU · ... · DXGI},
 * {@code SOFTWARE · llvmpipe}), and, when that line is clicked, a strip above the status bar with the device, the
 * driver, the Vulkan version and the present path. Closed, it takes no room; the window is short already.
 *
 * <p>Set once per run, when it begins, from the source's thread; the click comes on a handler worker. Both only
 * write props, which are queued.
 */
final class GraphicsView {

    private final StatusBar status;
    private final Node panel;
    private final Node facts;
    private final Node reason;
    private final Node device;
    private final Node kind;
    private final Node vendor;
    private final Node driver;
    private final Node vulkan;
    private final Node present;
    private final Node validation;
    private volatile Graphics shown = Graphics.NONE;
    private volatile boolean open;

    GraphicsView(Gui gui, StatusBar status) {
        this.status = status;
        status.slot(Landmarks.STATUS_GRAPHICS, StatusBar.Side.RIGHT, "");
        Node line = status.slot(Landmarks.STATUS_GRAPHICS);
        gui.landmark(Landmarks.STATUS_GRAPHICS, line);
        gui.onClick(line, this::toggle);

        facts = gui.row().gap(Length.rem(1.6f)).alignItems(AlignItems.START);
        device = readout(gui, facts, "Device", Landmarks.GRAPHICS_DEVICE);
        kind = readout(gui, facts, "Kind", Landmarks.GRAPHICS_KIND);
        vendor = readout(gui, facts, "Vendor", Landmarks.GRAPHICS_VENDOR);
        driver = readout(gui, facts, "Driver", Landmarks.GRAPHICS_DRIVER);
        vulkan = readout(gui, facts, "Vulkan", Landmarks.GRAPHICS_VULKAN);
        present = readout(gui, facts, "Present", Landmarks.GRAPHICS_PRESENT);
        validation = readout(gui, facts, "Validation", Landmarks.GRAPHICS_VALIDATION);
        reason = gui.text("").font("mono").textSize(Length.rem(0.78f)).textColor(gui.theme().color(Role.FAINT));
        gui.landmark(Landmarks.GRAPHICS_REASON, reason);
        panel = gui.column()
                .width(Length.FILL)
                .gap(Length.rem(0.4f))
                .padding(Length.rem(0.6f))
                .background(gui.theme().color(Role.PANEL))
                .children(gui.text("WHAT IT DRAWS WITH").textSize(Length.rem(0.7f))
                        .textColor(gui.theme().color(Role.ACCENT)), facts, reason);
        panel.visible(false);
        gui.landmark(Landmarks.GRAPHICS, panel);
    }

    /** The strip, which opens above the status bar. */
    Node node() {
        return panel;
    }

    /** A name over a value, which never wraps; returns the value. */
    private static Node readout(Gui gui, Node into, String name, String landmark) {
        Node value = gui.text("—").font("mono").textSize(Length.rem(0.85f)).textColor(gui.theme().color(Role.INK))
                .wordWrap(false);
        gui.landmark(landmark, value);
        into.append(gui.column().gap(Length.rem(0.1f)).children(
                gui.text(name).textSize(Length.rem(0.68f)).textColor(gui.theme().color(Role.FAINT)),
                value));
        return value;
    }

    /** Show what the application said. Any thread: writes are queued. */
    void show(Graphics g) {
        shown = g;
        status.text(Landmarks.STATUS_GRAPHICS, g.none() ? "" : g.summary() + (open ? "  −" : "  +"));
        device.text(g.device());
        kind.text(g.kind());
        vendor.text(g.vendor());
        driver.text(g.driver());
        vulkan.text(g.vulkan());
        present.text(g.present());
        validation.text(g.validation());
        reason.text(g.reason());
        facts.visible(g.known());
        reason.visible(!g.reason().isEmpty());
        panel.visible(open && !g.none());
    }

    /** Open or close the strip: a click on the status line's graphics. */
    private void toggle() {
        open = !open;
        show(shown);
    }
}
