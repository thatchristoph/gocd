/*************************GO-LICENSE-START*********************************
 * Copyright 2014 ThoughtWorks, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *************************GO-LICENSE-END***********************************/

package com.thoughtworks.go.server.materials;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import com.thoughtworks.go.config.CaseInsensitiveString;
import com.thoughtworks.go.config.CruiseConfig;
import com.thoughtworks.go.config.materials.ScmMaterial;
import com.thoughtworks.go.config.materials.svn.SvnMaterial;
import com.thoughtworks.go.config.materials.svn.SvnMaterialConfig;
import com.thoughtworks.go.domain.materials.Material;
import com.thoughtworks.go.domain.materials.MaterialConfig;
import com.thoughtworks.go.helper.MaterialConfigsMother;
import com.thoughtworks.go.helper.MaterialsMother;
import com.thoughtworks.go.helper.PipelineConfigMother;
import com.thoughtworks.go.metrics.domain.context.Context;
import com.thoughtworks.go.metrics.domain.probes.ProbeType;
import com.thoughtworks.go.metrics.service.MetricsProbeService;
import com.thoughtworks.go.server.domain.Username;
import com.thoughtworks.go.server.materials.postcommit.PostCommitHookImplementer;
import com.thoughtworks.go.server.materials.postcommit.PostCommitHookMaterialType;
import com.thoughtworks.go.server.materials.postcommit.PostCommitHookMaterialTypeResolver;
import com.thoughtworks.go.server.perf.MDUPerformanceLogger;
import com.thoughtworks.go.server.service.GoConfigService;
import com.thoughtworks.go.server.service.MaterialConfigConverter;
import com.thoughtworks.go.server.service.result.HttpLocalizedOperationResult;
import com.thoughtworks.go.serverhealth.HealthStateScope;
import com.thoughtworks.go.serverhealth.HealthStateType;
import com.thoughtworks.go.serverhealth.ServerHealthService;
import com.thoughtworks.go.serverhealth.ServerHealthState;
import com.thoughtworks.go.serverhealth.ServerHealthStates;
import com.thoughtworks.go.util.ProcessManager;
import com.thoughtworks.go.util.ReflectionUtil;
import com.thoughtworks.go.util.SystemEnvironment;
import org.apache.commons.httpclient.HttpStatus;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Matchers;
import org.mockito.Mockito;
import org.mockito.internal.verification.AtMost;

import static com.thoughtworks.go.helper.MaterialUpdateMessageMatcher.matchMaterialUpdateMessage;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.anySet;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MaterialUpdateServiceTest {
    private MaterialUpdateQueue queue;
    private MaterialUpdateCompletedTopic completed;
    private GoConfigService goConfigService;
    private static final SvnMaterialConfig MATERIAL_CONFIG = MaterialConfigsMother.svnMaterialConfig();
    private static final SvnMaterial MATERIAL = MaterialsMother.svnMaterial();
    private MaterialUpdateService service;
    private Username username;
    private HttpLocalizedOperationResult result;
    private PostCommitHookMaterialTypeResolver postCommitHookMaterialType;
    private PostCommitHookMaterialType validMaterialType;
    private PostCommitHookMaterialType invalidMaterialType;
    private ServerHealthService serverHealthService;
    private SystemEnvironment systemEnvironment;
    private MetricsProbeService metricsProbeService;
    private MaterialConfigConverter materialConfigConverter;

    @Before
    public void setUp() throws Exception {
        queue = mock(MaterialUpdateQueue.class);
        completed = mock(MaterialUpdateCompletedTopic.class);
        goConfigService = mock(GoConfigService.class);
        postCommitHookMaterialType = mock(PostCommitHookMaterialTypeResolver.class);
        serverHealthService = mock(ServerHealthService.class);
        systemEnvironment = new SystemEnvironment();
        metricsProbeService = mock(MetricsProbeService.class);
        materialConfigConverter = mock(MaterialConfigConverter.class);
        MDUPerformanceLogger mduPerformanceLogger = mock(MDUPerformanceLogger.class);
        service = new MaterialUpdateService(queue, completed, goConfigService, systemEnvironment, serverHealthService, postCommitHookMaterialType, mduPerformanceLogger, materialConfigConverter);
        HashSet<MaterialConfig> materialConfigs = new HashSet(Collections.singleton(MATERIAL_CONFIG));
        HashSet<Material> materials = new HashSet(Collections.singleton(MATERIAL));
        when(goConfigService.getSchedulableMaterials()).thenReturn(materialConfigs);
        when(materialConfigConverter.toMaterials(materialConfigs)).thenReturn(materials);
        username = new Username(new CaseInsensitiveString("loser"));
        result = new HttpLocalizedOperationResult();
        validMaterialType = mock(PostCommitHookMaterialType.class);
        when(validMaterialType.isKnown()).thenReturn(true);
        when(validMaterialType.isValid(anyString())).thenReturn(true);
        invalidMaterialType = mock(PostCommitHookMaterialType.class);
        when(invalidMaterialType.isKnown()).thenReturn(false);
        when(invalidMaterialType.isValid(anyString())).thenReturn(false);
    }

    @After
    public void teardown() throws Exception {
        systemEnvironment.reset(SystemEnvironment.MATERIAL_UPDATE_INACTIVE_TIMEOUT);
    }

    @Test
    public void shouldSendMaterialUpdateCheckMessageWhenTimerIsCalled() throws Exception {
        service.onTimer();
        Mockito.verify(queue).post(matchMaterialUpdateMessage(MATERIAL));
    }

    @Test
    public void shouldNotSendMaterialUpdateCheckMessageIfMaterialIsStillBeingChecked() throws Exception {
        service.onTimer();
        service.onTimer();
        Mockito.verify(queue, new AtMost(1)).post(matchMaterialUpdateMessage(MATERIAL));
    }

    @Test
    public void shouldReturn401WhenUserIsNotAnAdmin_WhenInvokingPostCommitHookMaterialUpdate() {
        when(goConfigService.isUserAdmin(username)).thenReturn(false);
        service.notifyMaterialsForUpdate(username, new HashMap(), result);
        assertThat(result.isSuccessful(), is(false));
        assertThat(result.httpCode(), is(HttpStatus.SC_UNAUTHORIZED));
        assertThat(result.hasMessage(), is(true));
        verify(goConfigService).isUserAdmin(username);
    }

    @Test
    public void shouldReturn400WhenTypeIsMissing_WhenInvokingPostCommitHookMaterialUpdate() {
        when(goConfigService.isUserAdmin(username)).thenReturn(true);
        service.notifyMaterialsForUpdate(username, new HashMap(), result);
        assertThat(result.isSuccessful(), is(false));
        assertThat(result.httpCode(), is(HttpStatus.SC_BAD_REQUEST));
        assertThat(result.hasMessage(), is(true));
        verify(goConfigService).isUserAdmin(username);
    }

    @Test
    public void shouldReturn400WhenTypeIsInvalid_WhenInvokingPostCommitHookMaterialUpdate() {
        when(goConfigService.isUserAdmin(username)).thenReturn(true);
        when(postCommitHookMaterialType.toType("some_invalid_type")).thenReturn(invalidMaterialType);
        final HashMap params = new HashMap();
        params.put(MaterialUpdateService.TYPE, "some_invalid_type");
        service.notifyMaterialsForUpdate(username, params, result);
        assertThat(result.isSuccessful(), is(false));
        assertThat(result.httpCode(), is(HttpStatus.SC_BAD_REQUEST));
        assertThat(result.hasMessage(), is(true));
        verify(goConfigService).isUserAdmin(username);
    }

    @Test
    public void shouldReturnImplementerOfSvnPostCommitHookAndPerformMaterialUpdate_WhenInvokingPostCommitHookMaterialUpdate() {
        final HashMap params = new HashMap();
        params.put(MaterialUpdateService.TYPE, "svn");
        when(goConfigService.isUserAdmin(username)).thenReturn(true);
        final CruiseConfig cruiseConfig = new CruiseConfig(PipelineConfigMother.createGroup("groupName", "pipeline1", "pipeline2"));
        when(goConfigService.currentCruiseConfig()).thenReturn(cruiseConfig);
        when(postCommitHookMaterialType.toType("svn")).thenReturn(validMaterialType);
        final PostCommitHookImplementer svnPostCommitHookImplementer = mock(PostCommitHookImplementer.class);
        final Material svnMaterial = mock(Material.class);
        when(svnPostCommitHookImplementer.prune(anySet(), eq(params))).thenReturn(new HashSet(Arrays.asList(svnMaterial)));
        when(validMaterialType.getImplementer()).thenReturn(svnPostCommitHookImplementer);
        final MaterialUpdateService spyService = spy(service);
        doNothing().when(spyService).updateMaterial(svnMaterial);
        spyService.notifyMaterialsForUpdate(username, params, result);
        verify(svnPostCommitHookImplementer).prune(anySet(), eq(params));
        verify(spyService).updateMaterial(svnMaterial);
    }

    @Test
    public void shouldUpdateServerHealthMessageWhenHung() {
        //given
        service = spy(service);
        systemEnvironment.set(SystemEnvironment.MATERIAL_UPDATE_INACTIVE_TIMEOUT, 1);
        ProcessManager processManager = mock(ProcessManager.class);
        Material material = mock(Material.class);
        service.updateMaterial(material);
        when(service.getProcessManager()).thenReturn(processManager);
        when(material.getFingerprint()).thenReturn("fingerprint");
        when(material.getUriForDisplay()).thenReturn("uri");
        when(material.getLongDescription()).thenReturn("details to uniquely identify a material");
        when(material.isAutoUpdate()).thenReturn(true);
        when(processManager.getIdleTimeFor("fingerprint")).thenReturn(60010L);

        //when
        service.updateMaterial(material);

        //then
        verify(serverHealthService).removeByScope(HealthStateScope.forMaterialUpdate(material));
        ArgumentCaptor<ServerHealthState> argumentCaptor = new ArgumentCaptor<ServerHealthState>();
        verify(serverHealthService).update(argumentCaptor.capture());
        assertThat(argumentCaptor.getValue().getMessage(), is("Material update for uri hung:"));
        assertThat(argumentCaptor.getValue().getDescription(),
                is("Material update is currently running but has not shown any activity in the last 1 minute(s). This may be hung. Details - details to uniquely identify a material"));
        assertThat(argumentCaptor.getValue().getType(), is(HealthStateType.general(HealthStateScope.forMaterialUpdate(material))));
    }

    @Test
    public void shouldNotUpdateServerHealthMessageWhenIdleTimeLessThanConfigured() {
        //given
        service = spy(service);
        systemEnvironment.set(SystemEnvironment.MATERIAL_UPDATE_INACTIVE_TIMEOUT, 2);
        ProcessManager processManager = mock(ProcessManager.class);
        Material material = mock(Material.class);
        service.updateMaterial(material);
        when(service.getProcessManager()).thenReturn(processManager);
        when(material.getFingerprint()).thenReturn("fingerprint");
        when(processManager.getIdleTimeFor("fingerprint")).thenReturn(60010L);

        //when
        service.updateMaterial(material);

        //then
        verify(serverHealthService, never()).removeByScope(HealthStateScope.forMaterialUpdate(material));
        verify(serverHealthService, never()).update(Matchers.<ServerHealthState>any());
    }

    @Test
    public void shouldRemoveServerHealthMessageOnMaterialUpdateCompletion() {
        Material material = mock(Material.class);
        when(material.getFingerprint()).thenReturn("fingerprint");
        service.onMessage(new MaterialUpdateCompletedMessage(material, 0));
        verify(serverHealthService).removeByScope(HealthStateScope.forMaterialUpdate(material));
    }

    @Test
    public void shouldMaterialUpdateShouldNotBeInProgressIfUpdateMaterialMessagePostFails() {
        doThrow(new RuntimeException("failed")).when(queue).post(matchMaterialUpdateMessage(MATERIAL));
        try {
            service.updateMaterial(MATERIAL);
            fail("Should have failed");
        } catch (RuntimeException e) {
            // should re-throw exception
        }
        Map<Material, Date> inProgress = (Map<Material, Date>) ReflectionUtil.getField(service, "inProgress");
        assertThat(inProgress.containsKey(MATERIAL), is(false));
    }

    @Test
    public void shouldCacheSchedulableMaterials() {
        service.onTimer();
        service.onTimer();
        verify(goConfigService).getSchedulableMaterials();
    }

    @Test
    public void shouldClearSchedulableMaterialCacheOnConfigChange() {
        when(serverHealthService.getAllLogs()).thenReturn(new ServerHealthStates());
        service.onTimer();
        service.onConfigChange(mock(CruiseConfig.class));
        service.onTimer();
        verify(goConfigService, times(2)).getSchedulableMaterials();
    }

    @Test
    public void shouldAllowPostCommitNotificationsToPassThroughToTheQueue_WhenTheSameMaterialIsCurrentlyInProgressAndMaterialIsAutoUpdateFalse() throws Exception {
        ScmMaterial material = mock(ScmMaterial.class);
        when(material.isAutoUpdate()).thenReturn(false);
        when(metricsProbeService.begin(ProbeType.MATERIAL_UPDATE_QUEUE_COUNTER)).thenReturn(mock(Context.class));
        MaterialUpdateMessage message = new MaterialUpdateMessage(material, 0);
        doNothing().when(queue).post(message);
        service.updateMaterial(material); //prune inprogress queue to have this material in it
        service.updateMaterial(material); // immediately notify another check-in
        verify(queue, times(2)).post(message);
        verify(material).isAutoUpdate();
    }

    @Test
    public void shouldNotAllowPostCommitNotificationsToPassThroughToTheQueue_WhenTheSameMaterialIsCurrentlyInProgressAndMaterialIsAutoUpdateTrue() throws Exception {
        ScmMaterial material = mock(ScmMaterial.class);
        when(material.isAutoUpdate()).thenReturn(true);
        when(metricsProbeService.begin(ProbeType.MATERIAL_UPDATE_QUEUE_COUNTER)).thenReturn(mock(Context.class));
        MaterialUpdateMessage message = new MaterialUpdateMessage(material, 0);
        doNothing().when(queue).post(message);
        service.updateMaterial(material); //prune inprogress queue to have this material in it
        service.updateMaterial(material); // immediately notify another check-in
        verify(queue, times(1)).post(message);
        verify(material).isAutoUpdate();
    }

    @Test
    public void shouldAllowPostCommitNotificationsToPassThroughToTheQueue_WhenTheSameMaterialIsNotCurrentlyInProgressAndMaterialIsAutoUpdateTrue() throws Exception {
        ScmMaterial material = mock(ScmMaterial.class);
        when(material.isAutoUpdate()).thenReturn(true);
        when(metricsProbeService.begin(ProbeType.MATERIAL_UPDATE_QUEUE_COUNTER)).thenReturn(mock(Context.class));
        MaterialUpdateMessage message = new MaterialUpdateMessage(material, 0);
        doNothing().when(queue).post(message);
        service.updateMaterial(material); // first call to the method
        verify(queue, times(1)).post(message);
        verify(material, never()).isAutoUpdate();
    }

    @Test
    public void shouldAllowPostCommitNotificationsToPassThroughToTheQueue_WhenTheSameMaterialIsNotCurrentlyInProgressAndMaterialIsAutoUpdateFalse() throws Exception {
        ScmMaterial material = mock(ScmMaterial.class);
        when(material.isAutoUpdate()).thenReturn(false);
        when(metricsProbeService.begin(ProbeType.MATERIAL_UPDATE_QUEUE_COUNTER)).thenReturn(mock(Context.class));
        MaterialUpdateMessage message = new MaterialUpdateMessage(material, 0);
        doNothing().when(queue).post(message);
        service.updateMaterial(material); // first call to the method
        verify(queue, times(1)).post(message);
        verify(material, never()).isAutoUpdate();
    }
}
