package dev.sibarum.inspector.graphics;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * What the inspected application draws with: its Vulkan device, the driver, and how its frames reach the screen, as
 * it answers {@code gpu device} over its link (docs/framework-notes.md FN-12). The question this answers first is the
 * one a person asks first: is it on a real GPU, or on a software rasterizer?
 *
 * <p>The reply is {@code ok gpu device} and then one {@code key=value} per line. The numbers are
 * {@code VkPhysicalDeviceProperties}' own, undecoded, so that every application says them the same way and the
 * decoding lives here once:
 *
 * <pre>
 * device=NVIDIA GeForce RTX 4070      deviceName
 * type=2                              deviceType: 0 other, 1 integrated, 2 discrete, 3 virtual, 4 cpu
 * vendor=4318                         vendorID (decimal or 0x hex)
 * driver=2373935104                   driverVersion, in the vendor's own packing
 * driver.name=NVIDIA                  VkPhysicalDeviceDriverProperties.driverName, when the driver has it
 * driver.info=566.36                  ... driverInfo, the version as the vendor prints it
 * api=4206879                         apiVersion
 * present=dxgi                        dxgi, or swapchain
 * present.mode=fifo                   the swapchain's present mode, when it is one
 * images=3                            buffers presented in turn
 * validation=off                      whether the validation layer is on
 * </pre>
 *
 * Any key may be missing, and keys it does not know are ignored, so the two ends can grow apart. Any other reply is
 * the reason the application did not say, shown as it came ({@code err not available yet: gpu needs ...}).
 *
 * @param reason why there are no facts, or empty when there are
 * @param facts  what the application said, in its order
 */
public record Graphics(String reason, Map<String, String> facts) {

    /** Nothing is being inspected. */
    public static final Graphics NONE = new Graphics("", Map.of());

    /** A recording says nothing about the device it was recorded on. */
    public static final Graphics RECORDED = new Graphics("a recording does not say", Map.of());

    /** What the source was given when the application did not answer at all: too old to know the verb, or gone. */
    public static Graphics unanswered(String why) {
        return new Graphics("it did not answer gpu device: " + why, Map.of());
    }

    /** The application's reply to {@code gpu device}. */
    public static Graphics parse(String reply) {
        if (reply == null || reply.isBlank()) {
            return unanswered("an empty reply");
        }
        String[] lines = reply.split("\r?\n");
        if (!lines[0].startsWith("ok")) {
            String why = lines[0].startsWith("err ") ? lines[0].substring(4) : lines[0];
            return new Graphics(why.strip(), Map.of());
        }
        Map<String, String> facts = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int eq = lines[i].indexOf('=');
            if (eq > 0) {
                facts.put(lines[i].substring(0, eq).strip(), lines[i].substring(eq + 1).strip());
            }
        }
        return new Graphics("", Map.copyOf(facts));
    }

    /** Whether the application said what it draws with. */
    public boolean known() {
        return reason.isEmpty() && !facts.isEmpty();
    }

    /** Whether nothing is attached, so there is nothing to say at all. */
    public boolean none() {
        return reason.isEmpty() && facts.isEmpty();
    }

    /**
     * Whether it draws on the CPU: a device that says it is one, or a known software rasterizer, whichever type it
     * claims (a WARP adapter behind Dozen says {@code integrated}).
     */
    public boolean software() {
        if (type() == 4) {
            return true;
        }
        String name = (get("device") + " " + get("driver.name")).toLowerCase(Locale.ROOT);
        for (String s : SOFTWARE) {
            if (name.contains(s)) {
                return true;
            }
        }
        return false;
    }

    private static final String[] SOFTWARE = {
            "llvmpipe", "lavapipe", "softpipe", "swiftshader", "basic render", "warp"};

    /**
     * One line for the status bar: {@code GPU · NVIDIA GeForce RTX 4070 · DXGI}, {@code SOFTWARE · llvmpipe ·
     * swapchain}, or that it was not said; empty when nothing is attached.
     */
    public String summary() {
        if (none()) {
            return "";
        }
        if (!known()) {
            return "graphics not reported";
        }
        String present = facts.containsKey("present") ? " · " + present() : "";
        return (software() ? "SOFTWARE · " : "GPU · ") + get("device", "unnamed device") + present;
    }

    /** The device's name. */
    public String device() {
        return get("device", "—");
    }

    /** What kind of device, in words: {@code discrete GPU}, {@code CPU (software)}. */
    public String kind() {
        String kind = switch (type()) {
            case 0 -> "other";
            case 1 -> "integrated GPU";
            case 2 -> "discrete GPU";
            case 3 -> "virtual GPU";
            case 4 -> "CPU";
            default -> software() ? "" : "—";
        };
        return software() ? (kind.isEmpty() ? "software" : kind + " (software)") : kind;
    }

    /** The vendor by name where it is a well-known one, else its id in hex. */
    public String vendor() {
        long id = number("vendor");
        if (id < 0) {
            return "—";
        }
        String name = switch ((int) id) {
            case 0x1002 -> "AMD";
            case 0x10DE -> "NVIDIA";
            case 0x8086 -> "Intel";
            case 0x13B5 -> "Arm";
            case 0x5143 -> "Qualcomm";
            case 0x106B -> "Apple";
            case 0x1414 -> "Microsoft";
            case 0x1AE0 -> "Google";
            case 0x10005 -> "Mesa";
            default -> "";
        };
        String hex = String.format("0x%04x", id);
        return name.isEmpty() ? hex : name + " (" + hex + ")";
    }

    /**
     * The driver: its name and the version as the vendor prints it when the driver says so, else the packed
     * {@code driverVersion} decoded the vendor's way.
     */
    public String driver() {
        String name = get("driver.name");
        String version = facts.containsKey("driver.info") ? get("driver.info") : driverVersion();
        String both = (name + " " + version).strip();
        return both.isEmpty() ? "—" : both;
    }

    /**
     * {@code driverVersion} decoded. NVIDIA packs 10.8.8.6 bits; Intel's Windows driver packs 18.14; everyone else
     * (AMD, Mesa, the Intel Mesa driver) uses Vulkan's own packing.
     */
    String driverVersion() {
        long v = number("driver");
        if (v < 0) {
            return "";
        }
        long vendor = number("vendor");
        if (vendor == 0x10DE) {
            return ((v >>> 22) & 0x3FF) + "." + ((v >>> 14) & 0xFF) + "." + ((v >>> 6) & 0xFF) + "." + (v & 0x3F);
        }
        if (vendor == 0x8086 && !get("driver.name").toLowerCase(Locale.ROOT).contains("mesa")) {
            return (v >>> 14) + "." + (v & 0x3FFF);
        }
        return vulkanVersion(v);
    }

    /** The Vulkan API version the device supports: {@code 1.3.280}. */
    public String vulkan() {
        long v = number("api");
        return v < 0 ? "—" : vulkanVersion(v);
    }

    /** How frames reach the screen: {@code DXGI}, or {@code swapchain · fifo · 3 images}. */
    public String present() {
        String path = get("present");
        StringBuilder sb = new StringBuilder(switch (path.toLowerCase(Locale.ROOT)) {
            case "dxgi" -> "DXGI";
            case "swapchain" -> "swapchain";
            case "" -> "—";
            default -> path;
        });
        if (facts.containsKey("present.mode")) {
            sb.append(" · ").append(get("present.mode"));
        }
        if (facts.containsKey("images")) {
            sb.append(" · ").append(get("images")).append(" images");
        }
        return sb.toString();
    }

    /** Whether the validation layer is on. */
    public String validation() {
        return get("validation", "—");
    }

    /** {@code VK_MAKE_API_VERSION}'s packing: 7 bits major, 10 minor, 12 patch, under 3 bits of variant. */
    static String vulkanVersion(long v) {
        return ((v >>> 22) & 0x7F) + "." + ((v >>> 12) & 0x3FF) + "." + (v & 0xFFF);
    }

    private int type() {
        return (int) number("type");
    }

    /** A fact as a number, decimal or {@code 0x} hex, or -1 when it is missing or not one. */
    private long number(String key) {
        try {
            return Long.decode(get(key));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private String get(String key) {
        return get(key, "");
    }

    private String get(String key, String otherwise) {
        String v = facts.get(key);
        return v == null || v.isEmpty() ? otherwise : v;
    }
}
