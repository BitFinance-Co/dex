package com.wavesplatform.it.sync

import cats.Id
import cats.instances.try_._
import com.typesafe.config.{Config, ConfigFactory}
import com.wavesplatform.dex.it.api.NodeApi
import com.wavesplatform.dex.it.cache.CachedData
import com.wavesplatform.dex.it.docker.WavesNodeContainer
import com.wavesplatform.dex.it.fp
import com.wavesplatform.it.MatcherSuiteBase
import com.wavesplatform.it.api.dex.OrderStatus
import com.wavesplatform.transaction.assets.exchange.OrderType
import monix.eval.Coeval

import scala.util.Try

class DEXClientFaultToleranceTestSuite extends MatcherSuiteBase {

  override protected val suiteInitialDexConfig: Config = {
    ConfigFactory.parseString(s"""waves.dex.price-assets = [ "$UsdId", "WAVES" ]""")
  }

  private val wavesNode2Container: Coeval[WavesNodeContainer] = Coeval.evalOnce { createWavesNode("waves-2") }

  protected val cachedWavesNode2ApiAddress = CachedData {
    dockerClient.getExternalSocketAddress(wavesNode2Container(), wavesNode2Container().restApiPort)
  }

  private def wavesNode2Api: NodeApi[Id] = fp.sync(NodeApi[Try]("integration-test-rest-api", cachedWavesNode2ApiAddress.get()))

  override protected def beforeAll(): Unit = {
    startAndWait(wavesNode1Container(), wavesNode1Api)
    broadcastAndAwait(IssueUsdTx)
    startAndWait(dex1Container(), dex1Api)
  }

  "DEXClient should works correctly despite of the short connection losses" in {
    val aliceBuyOrder = mkOrder(alice, wavesUsdPair, OrderType.BUY, 1.waves, 300)

    lazy val alice2BobTransferTx = mkTransfer(alice, bob, amount = wavesNode1Api.balance(alice, usd), asset = usd)
    lazy val bob2AliceTransferTx = mkTransfer(bob, alice, amount = wavesNode1Api.balance(bob, usd), asset = usd)

    markup("Alice places order that requires some amount of USD, DEX receives balances stream from the node 1")
    dex1Api.place(aliceBuyOrder)
    dex1Api.waitForOrderStatus(aliceBuyOrder, OrderStatus.Accepted)

    markup(s"Disconnect DEX from the network and perform USD transfer from Alice to Bob")
    dockerClient.disconnectFromNetwork(dex1Container)

    broadcastAndAwait(wavesNode1Api, alice2BobTransferTx)
    usdBalancesShouldBe(wavesNode1Api, 0, defaultAssetQuantity)

    Thread.sleep(2000)

    markup("Connect DEX back to the network, DEX should know about transfer and cancel Alice's order")
    dockerClient.connectToNetwork(dex1Container, None)
    invalidateCaches()

    dex1Api.waitForOrderStatus(aliceBuyOrder, OrderStatus.Cancelled)

    withClue("Cleanup") {
      broadcastAndAwait(wavesNode1Api, bob2AliceTransferTx)
    }
  }

  "DEXClient should switch nodes if connection to one of them was lost due to node shutdown" in {

    // also works for the cases when nodes are disconnected from the network (not stopped),
    // in these cases some delays after disconnections are required

    val aliceBuyOrder = mkOrder(alice, wavesUsdPair, OrderType.BUY, 1.waves, 300)
    val bobBuyOrder   = mkOrder(bob, wavesUsdPair, OrderType.BUY, 1.waves, 300)

    lazy val alice2BobTransferTx = mkTransfer(alice, bob, amount = wavesNode2Api.balance(alice, usd), asset = usd)
    lazy val bob2AliceTransferTx = mkTransfer(bob, alice, amount = wavesNode1Api.balance(bob, usd), asset = usd)

    markup("Alice places order that requires some amount of USD, DEX receives balances stream from the node 1")
    dex1Api.place(aliceBuyOrder)
    dex1Api.waitForOrderStatus(aliceBuyOrder, OrderStatus.Accepted)

    markup("Up node 2")
    dockerClient.start(wavesNode2Container)

    wavesNode2Api.waitReady
    wavesNode2Api.connect(wavesNode1NetworkApiAddress)
    wavesNode2Api.waitForConnectedPeer(wavesNode1NetworkApiAddress)

    wavesNode2Api.waitForTransaction(IssueUsdTx)

    markup(s"Stop node 1 and perform USD transfer from Alice to Bob")
    dockerClient.stop(wavesNode1Container)

    broadcastAndAwait(wavesNode2Api, alice2BobTransferTx)
    usdBalancesShouldBe(wavesNode2Api, expectedAliceBalance = 0, expectedBobBalance = defaultAssetQuantity)

    markup("Now DEX receives balances stream from the node 2 and cancels Alice's order")
    dex1Api.waitForOrderStatus(aliceBuyOrder, OrderStatus.Cancelled)

    markup("Bob places order that requires some amount of USD, DEX receives balances stream from the node 2")
    dex1Api.place(bobBuyOrder)
    dex1Api.waitForOrderStatus(bobBuyOrder, OrderStatus.Accepted)

    markup("Up node 1")
    dockerClient.start(wavesNode1Container)
    invalidateCaches()

    wavesNode1Api.waitReady
    wavesNode2Api.connect(wavesNode1NetworkApiAddress)
    wavesNode2Api.waitForConnectedPeer(wavesNode1NetworkApiAddress)
    wavesNode1Api.waitForTransaction(alice2BobTransferTx)

    markup(s"Stop node 2 and perform USD transfer from Bob to Alice")
    dockerClient.stop(wavesNode2Container)

    broadcastAndAwait(wavesNode1Api, bob2AliceTransferTx)
    usdBalancesShouldBe(wavesNode1Api, defaultAssetQuantity, 0)

    markup("Now DEX receives balances stream from the node 1 and cancels Bob's order")
    dex1Api.waitForOrderStatus(bobBuyOrder, OrderStatus.Cancelled)
  }

  "DEXClient should correctly handle gRPC errors" in {

    val order = mkOrder(alice, wavesUsdPair, OrderType.BUY, 1.waves, 300)

    dockerClient.stop(wavesNode1Container)

    dex1Api.tryPlace(order) should failWith(
      105906177,
      "Waves Node is unavailable, please retry later or contact with the administrator"
    )

    invalidateCaches()
    startAndWait(wavesNode1Container(), wavesNode1Api)

    dex1Api.place(order)
    dex1Api.waitForOrderStatus(order, OrderStatus.Accepted)
  }

  private def usdBalancesShouldBe(wavesNodeApi: NodeApi[Id], expectedAliceBalance: Long, expectedBobBalance: Long): Unit = {
    withClue("alice:")(wavesNodeApi.balance(alice, usd) shouldBe expectedAliceBalance)
    withClue("bob:")(wavesNodeApi.balance(bob, usd) shouldBe expectedBobBalance)
  }

  override protected def invalidateCaches(): Unit = {
    super.invalidateCaches()
    cachedWavesNode2ApiAddress.invalidate()
  }
}