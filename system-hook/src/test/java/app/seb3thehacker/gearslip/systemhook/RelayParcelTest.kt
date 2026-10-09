package app.seb3thehacker.gearslip.systemhook

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 36])
class RelayParcelTest : RelayContract()
