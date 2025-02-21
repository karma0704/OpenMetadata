package org.openmetadata.service.security.policyevaluator;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.openmetadata.common.utils.CommonUtil.listOf;
import static org.openmetadata.common.utils.CommonUtil.listOrEmpty;
import static org.openmetadata.service.security.policyevaluator.CompiledRule.parseExpression;
import static org.openmetadata.service.security.policyevaluator.SubjectContext.TEAM_FIELDS;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.openmetadata.schema.entity.data.Table;
import org.openmetadata.schema.entity.teams.Role;
import org.openmetadata.schema.entity.teams.Team;
import org.openmetadata.schema.entity.teams.User;
import org.openmetadata.schema.type.AssetCertification;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.Include;
import org.openmetadata.schema.type.TagLabel;
import org.openmetadata.service.Entity;
import org.openmetadata.service.jdbi3.EntityRepository;
import org.openmetadata.service.jdbi3.TableRepository;
import org.openmetadata.service.jdbi3.TeamRepository;
import org.openmetadata.service.security.policyevaluator.SubjectContext.PolicyContext;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

class RuleEvaluatorTest {
  private static final Table table = new Table().withName("table");
  private static User user;
  private static EvaluationContext evaluationContext;
  private static SubjectContext subjectContext;
  private static ResourceContext<?> resourceContext;

  @BeforeAll
  public static void setup() {
    TeamRepository teamRepository = mock(TeamRepository.class);
    Entity.registerEntity(Team.class, Entity.TEAM, teamRepository);
    Mockito.when(teamRepository.find(any(UUID.class), any(Include.class)))
        .thenAnswer(
            i ->
                EntityRepository.CACHE_WITH_ID.get(
                    new ImmutablePair<>(Entity.TEAM, i.getArgument(0))));
    Mockito.when(teamRepository.getReference(any(UUID.class), any(Include.class)))
        .thenAnswer(
            i ->
                EntityRepository.CACHE_WITH_ID
                    .get(new ImmutablePair<>(Entity.TEAM, i.getArgument(0)))
                    .getEntityReference());

    Mockito.when(teamRepository.findByName(anyString(), any(Include.class)))
        .thenAnswer(
            i ->
                EntityRepository.CACHE_WITH_NAME.get(
                    new ImmutablePair<>(Entity.TEAM, i.getArgument(0))));

    Mockito.when(
            teamRepository.get(
                isNull(), any(UUID.class), isNull(), any(Include.class), anyBoolean()))
        .thenAnswer(
            i ->
                EntityRepository.CACHE_WITH_ID.get(
                    new ImmutablePair<>(Entity.TEAM, i.getArgument(1))));

    Mockito.when(
            teamRepository.getByName(
                isNull(), anyString(), isNull(), any(Include.class), anyBoolean()))
        .thenAnswer(
            i ->
                EntityRepository.CACHE_WITH_ID.get(
                    new ImmutablePair<>(Entity.TEAM, i.getArgument(1))));

    TableRepository tableRepository = mock(TableRepository.class);
    Entity.registerEntity(Table.class, Entity.TABLE, tableRepository);
    Mockito.when(tableRepository.getAllTags(any()))
        .thenAnswer((Answer<List<TagLabel>>) invocationOnMock -> table.getTags());

    user = new User().withId(UUID.randomUUID()).withName("user");
    resourceContext = new ResourceContext<>("table", table, mock(TableRepository.class));
    subjectContext = new SubjectContext(user);
    RuleEvaluator ruleEvaluator = new RuleEvaluator(null, subjectContext, resourceContext);
    evaluationContext =
        SimpleEvaluationContext.forReadOnlyDataBinding().withRootObject(ruleEvaluator).build();
  }

  @Test
  void test_noOwner() {
    // Set no owner to the entity and test noOwner method
    table.setOwners(null);
    assertTrue(evaluateExpression("noOwner()"));
    assertFalse(evaluateExpression("!noOwner()"));

    // Set owner to the entity and test noOwner method
    table.setOwners(List.of(new EntityReference().withId(UUID.randomUUID()).withType(Entity.USER)));
    assertFalse(evaluateExpression("noOwner()"));
    assertTrue(evaluateExpression("!noOwner()"));
  }

  @Test
  void test_isOwner() {
    // Table owner is a different user (random ID) and hence isOwner returns false
    table.setOwners(
        List.of(
            new EntityReference()
                .withId(UUID.randomUUID())
                .withType(Entity.USER)
                .withName("otherUser")));
    assertFalse(evaluateExpression("isOwner()"));
    assertTrue(evaluateExpression("!isOwner()"));

    // Table owner is same as the user in subjectContext and hence isOwner returns true
    table.setOwners(
        List.of(
            new EntityReference()
                .withId(user.getId())
                .withType(Entity.USER)
                .withName(user.getName())));
    assertTrue(evaluateExpression("isOwner()"));
    assertFalse(evaluateExpression("!isOwner()"));

    // noOwner() || isOwner() - with noOwner being true and isOwner false
    table.setOwners(null);
    assertTrue(evaluateExpression("noOwner() || isOwner()"));
    assertFalse(evaluateExpression("!noOwner() && !isOwner()"));

    // noOwner() || isOwner() - with noOwner is false and isOwner true
    table.setOwners(
        List.of(
            new EntityReference()
                .withId(user.getId())
                .withType(Entity.USER)
                .withName(user.getName())));
    assertTrue(evaluateExpression("noOwner() || isOwner()"));
    assertFalse(evaluateExpression("!noOwner() && !isOwner()"));
  }

  @Test
  void test_matchAllTags() {
    table.withTags(getTags("tag1", "tag2", "tag3"));

    // All tags present
    assertTrue(evaluateExpression("matchAllTags('tag1', 'tag2', 'tag3')"));
    assertFalse(evaluateExpression("!matchAllTags('tag1', 'tag2', 'tag3')"));
    assertTrue(evaluateExpression("matchAllTags('tag1', 'tag2')"));
    assertFalse(evaluateExpression("!matchAllTags('tag1', 'tag2')"));
    assertTrue(evaluateExpression("matchAllTags('tag1')"));
    assertFalse(evaluateExpression("!matchAllTags('tag1')"));

    // Tag 'tag4' is missing
    assertFalse(evaluateExpression("matchAllTags('tag1', 'tag2', 'tag3', 'tag4')"));
    assertTrue(evaluateExpression("!matchAllTags('tag1', 'tag2', 'tag3', 'tag4')"));
    assertFalse(evaluateExpression("matchAllTags('tag1', 'tag2', 'tag4')"));
    assertTrue(evaluateExpression("!matchAllTags('tag1', 'tag2', 'tag4')"));
    assertFalse(evaluateExpression("matchAllTags('tag2', 'tag4')"));
    assertTrue(evaluateExpression("!matchAllTags('tag2', 'tag4')"));
    assertFalse(evaluateExpression("matchAllTags('tag4')"));
    assertTrue(evaluateExpression("!matchAllTags('tag4')"));
  }

  @Test
  void test_matchAnyTag() {
    table.withTags(getTags("tag1", "tag2", "tag3"));

    // Tag is present
    assertTrue(evaluateExpression("matchAnyTag('tag1', 'tag2', 'tag3', 'tag4')"));
    assertFalse(evaluateExpression("!matchAnyTag('tag1', 'tag2', 'tag3', 'tag4')"));
    assertTrue(evaluateExpression("matchAnyTag('tag1', 'tag2', 'tag4')"));
    assertFalse(evaluateExpression("!matchAnyTag('tag1', 'tag2', 'tag4')"));
    assertTrue(evaluateExpression("matchAnyTag('tag1', 'tag2', 'tag4')"));
    assertFalse(evaluateExpression("!matchAnyTag('tag1', 'tag2', 'tag4')"));
    assertTrue(evaluateExpression("matchAnyTag('tag1', 'tag4')"));
    assertFalse(evaluateExpression("!matchAnyTag('tag1', 'tag4')"));

    // Tag `tag4` is not present
    assertFalse(evaluateExpression("matchAnyTag('tag4')"));
    assertTrue(evaluateExpression("!matchAnyTag('tag4')"));
  }

  @Test
  void test_matchAnyCertification() {
    // Certification is not Present
    assertFalse(evaluateExpression("!matchAnyCertification('Certification.Gold')"));
    assertFalse(
        evaluateExpression("!matchAnyCertification('Certification.Gold', 'Certification.Silver')"));
    assertFalse(evaluateExpression("matchAnyCertification('Certification.Bronze')"));

    table.withCertification(
        new AssetCertification().withTagLabel(new TagLabel().withTagFQN("Certification.Gold")));

    // Certification is present
    assertTrue(
        evaluateExpression("matchAnyCertification('Certification.Gold', 'Certification.Silver')"));
    assertFalse(
        evaluateExpression("!matchAnyCertification('Certification.Gold', 'Certification.Silver')"));
    assertTrue(evaluateExpression("matchAnyCertification('Certification.Gold')"));
    assertFalse(evaluateExpression("!matchAnyCertification('Certification.Gold')"));

    // Certification is different
    assertFalse(
        evaluateExpression(
            "matchAnyCertification('Certification.Bronze', 'Certification.Silver')"));
    assertTrue(
        evaluateExpression(
            "!matchAnyCertification('Certification.Bronze', 'Certification.Silver')"));
    assertFalse(evaluateExpression("matchAnyCertification('Certification.Bronze')"));
    assertTrue(evaluateExpression("!matchAnyCertification('Certification.Bronze')"));
  }

  @Test
  void test_matchTeam() {
    // Create a team hierarchy
    Team team1 = createTeam("team1", null);
    Team team11 = createTeam("team11", "team1");
    Team team12 = createTeam("team12", "team1");
    Team team111 = createTeam("team111", "team11");

    // Resource belongs to team111 and the Policy executed is coming from team111
    table.setOwners(List.of(team111.getEntityReference()));
    updatePolicyContext("team111");
    for (Team team : listOf(team111)) { // For users in team111 hierarchy matchTeam is true
      user.setTeams(listOf(team.getEntityReference()));
      assertTrue(evaluateExpression("matchTeam()"));
    }
    for (Team team :
        listOf(team1, team12, team11)) { // For users not in team111 hierarchy matchTeam is false
      user.setTeams(listOf(team.getEntityReference()));
      assertFalse(evaluateExpression("matchTeam()"), "Failed for team " + team.getName());
    }

    // Resource belongs to team111 and the Policy executed is coming from team11
    updatePolicyContext("team11");
    for (Team team : listOf(team11, team111)) { // For users in team11 hierarchy matchTeam is true
      user.setTeams(listOf(team.getEntityReference()));
      assertTrue(evaluateExpression("matchTeam()"));
    }
    for (Team team :
        listOf(team1, team12)) { // For users not in team11 hierarchy matchTeam is false
      user.setTeams(listOf(team.getEntityReference()));
      assertFalse(evaluateExpression("matchTeam()"), "Failed for team " + team.getName());
    }

    // Resource belongs to team111 and the Policy executed is coming from team1
    updatePolicyContext("team1");
    for (Team team :
        listOf(team1, team11, team111, team12)) { // For users in team1 hierarchy matchTeam is true
      user.setTeams(listOf(team.getEntityReference()));
      assertTrue(evaluateExpression("matchTeam()"));
    }
  }

  @Test
  void test_inAnyTeam() {
    // Create a team hierarchy
    Team team1 = createTeam("team1", null);
    createTeam("team11", "team1");
    Team team12 = createTeam("team12", "team1");
    Team team111 = createTeam("team111", "team11");

    // User in team111 - that means user is also in parent teams team11 and team1
    user.setTeams(listOf(team111.getEntityReference()));
    assertTrue(evaluateExpression("inAnyTeam('team1')"));
    assertTrue(evaluateExpression("inAnyTeam('team11')"));
    assertTrue(evaluateExpression("inAnyTeam('team111')"));
    assertFalse(evaluateExpression("inAnyTeam('team12')"));

    // User in team12 - that means user is also in parent team team1
    user.setTeams(listOf(team12.getEntityReference()));
    assertTrue(evaluateExpression("inAnyTeam('team1')"));
    assertTrue(evaluateExpression("inAnyTeam('team12')"));
    assertFalse(evaluateExpression("inAnyTeam('team111', 'team11')"));

    // User in team1 with no parents
    user.setTeams(listOf(team1.getEntityReference()));
    assertTrue(evaluateExpression("inAnyTeam('team1')"));
    assertFalse(evaluateExpression("inAnyTeam('team12', 'team11', 'team111')"));
  }

  @Test
  void test_hasAnyRole() {
    // Create a team hierarchy
    Team team1 = createTeamWithRole("team1", null);
    Team team11 = createTeamWithRole("team11", "team1");
    Team team111 = createTeamWithRole("team111", "team11");
    user.setRoles(listOf(createRole("user").getEntityReference()));

    // User in team111 inherits all roles
    user.setTeams(listOf(team111.getEntityReference()));
    for (String role : listOf("user", "team111", "team11", "team1")) {
      assertTrue(evaluateExpression(String.format("hasAnyRole('%s')", role)));
    }

    // User in team11 inherits all roles except team111
    user.setTeams(listOf(team11.getEntityReference()));
    for (String role : listOf("user", "team11", "team1")) {
      assertTrue(evaluateExpression(String.format("hasAnyRole('%s')", role)));
    }

    // User in team1 does not have parent team to inherit from
    user.setTeams(listOf(team1.getEntityReference()));
    for (String role : listOf("user", "team1")) {
      assertTrue(evaluateExpression(String.format("hasAnyRole('%s')", role)));
    }
  }

  private Boolean evaluateExpression(String condition) {
    return parseExpression(condition).getValue(evaluationContext, Boolean.class);
  }

  private List<TagLabel> getTags(String... tags) {
    List<TagLabel> tagLabels = new ArrayList<>();
    for (String tag : tags) {
      tagLabels.add(new TagLabel().withTagFQN(tag));
    }
    return tagLabels;
  }

  private Team createTeam(String teamName, String parentName) {
    UUID teamId = UUID.nameUUIDFromBytes(teamName.getBytes(StandardCharsets.UTF_8));
    Team team = new Team().withName(teamName).withId(teamId);
    if (parentName != null) {
      UUID parentId = UUID.nameUUIDFromBytes(parentName.getBytes(StandardCharsets.UTF_8));
      EntityReference parentTeam =
          Entity.getEntityReferenceById(Entity.TEAM, parentId, Include.NON_DELETED);
      team.setParents(listOf(parentTeam));
    }
    EntityRepository.CACHE_WITH_ID.put(new ImmutablePair<>(Entity.TEAM, team.getId()), team);
    return team;
  }

  private Team createTeamWithRole(String teamName, String parentName) {
    Team team = createTeam(teamName, parentName);
    Role role = createRole(teamName); // Create a role with same name as the teamName
    team.setDefaultRoles(listOf(role.getEntityReference()));
    team.setInheritedRoles(new ArrayList<>());
    for (EntityReference parent : listOrEmpty(team.getParents())) {
      Team parentTeam =
          Entity.getEntity(Entity.TEAM, parent.getId(), TEAM_FIELDS, Include.NON_DELETED);
      team.getInheritedRoles().addAll(listOrEmpty(parentTeam.getDefaultRoles()));
      team.getInheritedRoles().addAll(listOrEmpty(parentTeam.getInheritedRoles()));
    }
    return team;
  }

  private Role createRole(String roleName) {
    UUID roleId = UUID.nameUUIDFromBytes(roleName.getBytes(StandardCharsets.UTF_8));
    Role role = new Role().withName(roleName).withId(roleId);
    EntityRepository.CACHE_WITH_ID.put(new ImmutablePair<>(Entity.ROLE, role.getId()), role);
    return role;
  }

  private void updatePolicyContext(String team) {
    PolicyContext policyContext = new PolicyContext(Entity.TEAM, team, null, null, null);
    RuleEvaluator ruleEvaluator = new RuleEvaluator(policyContext, subjectContext, resourceContext);
    evaluationContext =
        SimpleEvaluationContext.forReadOnlyDataBinding().withRootObject(ruleEvaluator).build();
  }
}
