package dev.sibarum.inspector;

import dev.vexelray.framework.api.VexelApp;

/**
 * The release edition's application declaration: {@link Inspector}'s facts and no starters. In particular no
 * {@code AutomationStarter}, and the pom drops {@code vexelray-framework-automation} from the class path under
 * {@code -Pnative-release}, so a shipped binary cannot open a driving socket. The debug edition is
 * {@code src/edition-debug}; keep the two annotations identical apart from {@code starters}.
 */
@VexelApp(name = Inspector.APP, title = Inspector.TITLE, width = Inspector.W, height = Inspector.H,
        icon = Inspector.ICON)
final class InspectorApp {

    private InspectorApp() {
    }
}
