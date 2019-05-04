/*
 * Copyright 2014-2019 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.iep.lwc.fwd.admin
import akka.actor.ActorSystem
import akka.actor.Props
import akka.pattern.AskTimeoutException
import akka.pattern.ask
import akka.stream.scaladsl.Flow
import akka.testkit.DefaultTimeout
import akka.testkit.ImplicitSender
import akka.testkit.TestKit
import com.netflix.iep.lwc.fwd.admin.ScalingPolicies.GetCache
import com.netflix.iep.lwc.fwd.admin.ScalingPolicies.GetScalingPolicy
import com.netflix.iep.lwc.fwd.admin.ScalingPolicies.RefreshCache
import com.netflix.iep.lwc.fwd.cw.FwdMetricInfo
import com.typesafe.config.ConfigFactory
import org.scalatest.FunSuiteLike

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class ScalingPoliciesSuite
    extends TestKit(ActorSystem())
    with FunSuiteLike
    with DefaultTimeout
    with ImplicitSender {

  private val config = ConfigFactory.load()

  test("Get cached Scaling policy") {
    val ec2Policy1 = ScalingPolicy("ec2Policy1", ScalingPolicy.Ec2, "metric1", Nil)
    val data = Map(EddaEndpoint("123", "us-east-1", "test") -> List(ec2Policy1))

    val scalingPolicies = system.actorOf(
      Props[ScalingPoliciesTestImpl](
        new ScalingPoliciesTestImpl(
          config,
          new ScalingPoliciesDaoTestImpl(Map.empty[EddaEndpoint, List[ScalingPolicy]]),
          data
        )
      )
    )

    val future = scalingPolicies ? GetScalingPolicy(
      FwdMetricInfo("us-east-1", "123", "metric1", Map.empty[String, String])
    )
    val actual = Await.result(future.mapTo[Option[ScalingPolicy]], Duration.Inf)
    val expected = Some(ec2Policy1)
    assert(actual === expected)
  }

  test("Lookup Scaling policy") {
    val ec2Policy1 = ScalingPolicy("ec2Policy1", ScalingPolicy.Ec2, "metric1", Nil)
    val data = Map(EddaEndpoint("123", "us-east-1", "test") -> List(ec2Policy1))
    val scalingPolicies = system.actorOf(
      Props[ScalingPoliciesTestImpl](
        new ScalingPoliciesTestImpl(
          config,
          new ScalingPoliciesDaoTestImpl(policies = data)
        )
      )
    )

    var future = scalingPolicies ? GetScalingPolicy(
      FwdMetricInfo("us-east-1", "123", "metric1", Map.empty[String, String])
    )
    val actual = Await.result(future.mapTo[Option[ScalingPolicy]], Duration.Inf)

    val expected = Some(ec2Policy1)
    assert(actual === expected)

    future = scalingPolicies ? GetCache
    val cachedScalingPolicies =
      Await.result(future.mapTo[Map[EddaEndpoint, List[ScalingPolicy]]], Duration.Inf)
    assert(cachedScalingPolicies === data)
  }

  test("Timeout when dao fails to lookup Edda") {
    val scalingPolicies = system.actorOf(
      Props[ScalingPoliciesTestImpl](
        new ScalingPoliciesTestImpl(
          config,
          () => {
            Flow[EddaEndpoint]
              .filter(_ => false)
              .map(_ => List.empty[ScalingPolicy])
          }
        )
      )
    )
    val future = scalingPolicies ? GetScalingPolicy(
      FwdMetricInfo("us-east-1", "123", "metric1", Map.empty[String, String])
    )

    assertThrows[AskTimeoutException](
      Await.result(future.mapTo[Option[ScalingPolicy]], Duration.Inf)
    )
  }

  test("Refresh cache") {
    val ec2Policy1 = ScalingPolicy("ec2Policy1", ScalingPolicy.Ec2, "metric1", Nil)
    val ec2Policy2 = ScalingPolicy("ec2Policy2", ScalingPolicy.Ec2, "metric2", Nil)

    val eddaEndpoint = EddaEndpoint("123", "us-east-1", "test")
    val cache = Map(eddaEndpoint -> List(ec2Policy1))
    val data = Map(eddaEndpoint  -> List(ec2Policy1, ec2Policy2))

    val scalingPolicies = system.actorOf(
      Props[ScalingPoliciesTestImpl](
        new ScalingPoliciesTestImpl(
          config,
          new ScalingPoliciesDaoTestImpl(policies = data),
          cache
        )
      )
    )

    var future = scalingPolicies ? RefreshCache
    Await.ready(future, Duration.Inf)

    future = scalingPolicies ? GetCache
    val actual = Await.result(future.mapTo[Map[EddaEndpoint, List[ScalingPolicy]]], Duration.Inf)

    assert(actual === data)
  }

  test("Dao failure during cache refresh") {
    val ec2Policy1 = ScalingPolicy("ec2Policy1", ScalingPolicy.Ec2, "metric1", Nil)
    val cache = Map(EddaEndpoint("123", "us-east-1", "test") -> List(ec2Policy1))

    val scalingPolicies = system.actorOf(
      Props[ScalingPoliciesTestImpl](
        new ScalingPoliciesTestImpl(
          config,
          () => {
            Flow[EddaEndpoint]
              .filter(_ => false)
              .map(_ => List.empty[ScalingPolicy])
          },
          cache
        )
      )
    )

    var future = scalingPolicies ? RefreshCache
    Await.ready(future, Duration.Inf)

    future = scalingPolicies ? GetCache
    val actual = Await.result(future.mapTo[Map[EddaEndpoint, List[ScalingPolicy]]], Duration.Inf)
    assert(actual === cache)
  }

}