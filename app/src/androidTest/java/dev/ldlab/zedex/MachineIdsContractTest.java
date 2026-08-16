package dev.ldlab.zedex;

import dev.ldlab.zedex.welcome.pages.MachinePage;

import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.List;

/**
 * The wizard's machine list against Fuse's own.
 *
 * The wizard runs before Fuse starts, so it cannot ask
 * FuseNative.machineIds() what exists - that array is empty until the
 * emulation thread is up, which is why SettingsActivity disables its machine
 * row when it comes back empty. The list is therefore written down, and this
 * is what keeps it honest.
 *
 * <b>Asserted, never skipped on an empty array.</b> A test that reads
 * machineIds(), finds it empty and calls assumeTrue reports OK having
 * asserted nothing - which is how a mapping pointing at an id Fuse does not
 * have once passed review. So the emulator is launched first and the array
 * itself is an assertion.
 *
 * {@code Emulator} is a plain helper here, not a JUnit {@code @Rule} - it
 * implements no rule interface, and every other test in this package
 * (CaptureTest and its siblings) drives it the same way, as a field launched
 * from the test itself.
 */
@RunWith(AndroidJUnit4.class)
public class MachineIdsContractTest {

    private final Emulator emulator = new Emulator();

    @Test
    public void everyOfferedMachineIsOneFuseHas() {
        emulator.launch();

        String[] ids = FuseNative.machineIds();

        assertTrue("Fuse has not started: machineIds() is empty, so this test "
                 + "would assert nothing", ids.length > 0);

        List<String> known = Arrays.asList(ids);

        for (MachinePage.Model machine : MachinePage.MACHINES) {
            assertTrue("the wizard offers a machine Fuse does not have: "
                     + machine.id + " (" + machine.name + "), Fuse has "
                     + known, known.contains(machine.id));
        }
    }
}
