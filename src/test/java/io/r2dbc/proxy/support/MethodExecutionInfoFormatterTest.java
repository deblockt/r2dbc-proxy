/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.r2dbc.proxy.support;

import io.r2dbc.proxy.core.ConnectionInfo;
import io.r2dbc.proxy.core.MethodExecutionInfo;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author Tadaya Tsuyukubo
 */
public class MethodExecutionInfoFormatterTest {

    @Test
    void withDefault() {

        // String#indexOf(int) method
        Method method = ReflectionUtils.findMethod(String.class, "indexOf", int.class);

        Long target = 100L;

        ConnectionInfo connectionInfo = mock(ConnectionInfo.class);
        when(connectionInfo.getConnectionId()).thenReturn("ABC");

        MethodExecutionInfo executionInfo = new MethodExecutionInfo();
        executionInfo.setThreadId(5);
        executionInfo.setConnectionInfo(connectionInfo);
        executionInfo.setExecuteDuration(Duration.of(23, ChronoUnit.MILLIS));
        executionInfo.setMethod(method);
        executionInfo.setTarget(target);

        MethodExecutionInfoFormatter formatter = MethodExecutionInfoFormatter.withDefault();
        String result = formatter.format(executionInfo);

        assertThat(result).isEqualTo("  1: Thread:5 Connection:ABC Time:23  Long#indexOf()");

        // second time should increase the sequence
        result = formatter.format(executionInfo);
        assertThat(result).isEqualTo("  2: Thread:5 Connection:ABC Time:23  Long#indexOf()");

    }

    @Test
    void nullConnectionId() {

        // connection id is null for before execution of "ConnectionFactory#create"
        Method method = ReflectionUtils.findMethod(ConnectionFactory.class, "create");

        Long target = 100L;

        // null ConnectionInfo
        MethodExecutionInfo executionInfo = new MethodExecutionInfo();
        executionInfo.setThreadId(5);
        executionInfo.setConnectionInfo(null);
        executionInfo.setExecuteDuration(Duration.of(23, ChronoUnit.MILLIS));
        executionInfo.setMethod(method);
        executionInfo.setTarget(target);

        MethodExecutionInfoFormatter formatter = MethodExecutionInfoFormatter.withDefault();
        String result = formatter.format(executionInfo);

        assertThat(result).isEqualTo("  1: Thread:5 Connection:n/a Time:23  Long#create()");

        // null ConnectionId
        ConnectionInfo connectionInfo = mock(ConnectionInfo.class);
        when(connectionInfo.getConnectionId()).thenReturn(null);
        executionInfo.setConnectionInfo(connectionInfo);

        result = formatter.format(executionInfo);

        assertThat(result).isEqualTo("  2: Thread:5 Connection:n/a Time:23  Long#create()");
    }

    @Test
    void customConsumer() {

        MethodExecutionInfoFormatter formatter = new MethodExecutionInfoFormatter();
        formatter.addConsumer((executionInfo, sb) -> {
            sb.append("ABC");
        });
        String result = formatter.format(new MethodExecutionInfo());

        assertThat(result).isEqualTo("ABC");

    }

}
