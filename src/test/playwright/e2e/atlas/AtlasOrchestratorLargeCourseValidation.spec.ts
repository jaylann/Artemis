import { expect, type APIResponse, type Page } from '@playwright/test';
import fs from 'fs/promises';
import path from 'path';

import type { Course } from 'app/course/shared/entities/course.model';
import type { TextExercise } from 'app/text/shared/entities/text-exercise.model';

import textExerciseTemplate from '../../fixtures/exercise/text/template.json';
import { test } from '../../support/fixtures';
import { admin } from '../../support/users';

// TODO(BA thesis validation): Remove this temporary >50-exercise harness after the evidence is captured.

const LARGE_COURSE_VALIDATION_ENABLED = process.env.ATLAS_LARGE_COURSE_VALIDATION === '1';
const VALIDATION_EXERCISE_COUNT = 60;
const VALIDATION_COMPETENCY_COUNT = 12;
const EXERCISES_PER_TOPIC = 5;
const VALIDATION_OUTPUT_DIRECTORY = path.resolve(__dirname, '../../../../..', 'build/atlas-validation');
const COMPETENCY_TAXONOMIES = ['REMEMBER', 'UNDERSTAND', 'APPLY', 'ANALYZE', 'EVALUATE', 'CREATE'] as const;

const topics = [
    { name: 'Data Structures', competencyDescription: 'Represent and reason about arrays, trees, graphs, and hash tables.' },
    { name: 'Algorithms', competencyDescription: 'Select and analyze algorithms for searching, sorting, and optimization.' },
    { name: 'Databases', competencyDescription: 'Design relational schemas and reason about queries, indexes, and transactions.' },
    { name: 'Computer Networks', competencyDescription: 'Explain network protocols, addressing, routing, and reliable delivery.' },
    { name: 'Operating Systems', competencyDescription: 'Reason about processes, scheduling, memory, and file systems.' },
    { name: 'Software Engineering', competencyDescription: 'Apply maintainable design, testing, version control, and delivery practices.' },
    { name: 'Programming Languages', competencyDescription: 'Compare language paradigms, types, scopes, and runtime behavior.' },
    { name: 'Computer Security', competencyDescription: 'Identify threats and apply authentication, authorization, and secure design.' },
    { name: 'Distributed Systems', competencyDescription: 'Analyze coordination, consistency, failure handling, and replication.' },
    { name: 'Web Development', competencyDescription: 'Build accessible, stateful web applications across browser and server boundaries.' },
    { name: 'Machine Learning', competencyDescription: 'Describe model training, evaluation, generalization, and data preparation.' },
    { name: 'Human-Computer Interaction', competencyDescription: 'Evaluate interfaces with usability, accessibility, and user-centered methods.' },
] as const;

const pedagogicalFrames = [
    { label: 'Recall', instruction: 'Define the central terms and state the key invariant.' },
    { label: 'Compare', instruction: 'Compare two approaches and identify the important trade-off.' },
    { label: 'Apply', instruction: 'Apply the concept to a small, concrete scenario.' },
    { label: 'Analyze', instruction: 'Analyze a failure mode or surprising result and explain its cause.' },
    { label: 'Design', instruction: 'Design a short solution and justify the decisions that make it appropriate.' },
] as const;

type OrchestrationResponse = {
    status?: string;
    summary?: string;
    appliedActions?: unknown[];
    failureReason?: string;
};

type OrchestrationEvidence = {
    exerciseId: number;
    exerciseTitle: string;
    startedAt: string;
    completedAt: string;
    elapsedMs: number;
    httpStatus: number;
    status: string | null;
    actionCount: number;
    summary?: string;
    failureReason?: string;
};

async function readResponse(response: APIResponse): Promise<OrchestrationResponse> {
    const body = await response.text();
    if (!body) {
        return {};
    }

    try {
        return JSON.parse(body) as OrchestrationResponse;
    } catch {
        return {};
    }
}

async function runOrchestration(page: Page, exercise: TextExercise): Promise<OrchestrationEvidence> {
    const startedAtMs = Date.now();
    const startedAt = new Date(startedAtMs).toISOString();
    const response = await page.request.post(`api/atlas/orchestrator/exercises/${exercise.id}/run`);
    const completedAtMs = Date.now();
    const completedAt = new Date(completedAtMs).toISOString();
    const result = await readResponse(response);

    return {
        exerciseId: exercise.id!,
        exerciseTitle: exercise.title!,
        startedAt,
        completedAt,
        elapsedMs: completedAtMs - startedAtMs,
        httpStatus: response.status(),
        status: result.status ?? null,
        actionCount: result.appliedActions?.length ?? 0,
        ...(result.summary ? { summary: result.summary } : {}),
        ...(result.failureReason ? { failureReason: result.failureReason } : {}),
    };
}

test.describe('Atlas orchestrator large-course validation (temporary)', { tag: '@slow' }, () => {
    test.skip(!LARGE_COURSE_VALIDATION_ENABLED, 'Set ATLAS_LARGE_COURSE_VALIDATION=1 to run the paid-call validation experiment.');
    test.setTimeout(30 * 60 * 1000);

    let course: Course | undefined;

    test('creates a 60-exercise course and records three synchronous orchestration runs', async ({ page, login, courseManagementAPIRequests, exerciseAPIRequests }) => {
        await login(admin);
        course = await courseManagementAPIRequests.createCourse({ courseName: 'Atlas Large Course Validation' });
        expect(course.id).toBeDefined();
        console.log(`Created disposable Atlas large-course validation course ${course.id}; retain it until token evidence is queried, then delete it.`);

        const courseResponse = await page.request.get(`api/course/courses/${course.id}`);
        expect(courseResponse.ok()).toBeTruthy();
        const persistedCourse = (await courseResponse.json()) as { courseConfiguration?: { autoOrchestratorEnabled?: boolean } };
        expect(persistedCourse.courseConfiguration?.autoOrchestratorEnabled).toBe(false);

        const competencies = [];
        for (const [index, topic] of topics.entries()) {
            const competency = await courseManagementAPIRequests.createCompetency(
                course,
                `Atlas validation competency ${String(index + 1).padStart(2, '0')} - ${topic.name}`,
                topic.competencyDescription,
                COMPETENCY_TAXONOMIES[index % COMPETENCY_TAXONOMIES.length],
            );
            competencies.push(competency);
        }
        expect(competencies).toHaveLength(VALIDATION_COMPETENCY_COUNT);

        const exercises: TextExercise[] = [];
        for (const [topicIndex, topic] of topics.entries()) {
            for (const [frameIndex, frame] of pedagogicalFrames.entries()) {
                const exerciseNumber = topicIndex * EXERCISES_PER_TOPIC + frameIndex + 1;
                const title = `Atlas validation exercise ${String(exerciseNumber).padStart(2, '0')} - ${topic.name} - ${frame.label}`;
                const exercise = await exerciseAPIRequests.createTextExercise({ course }, title, {
                    ...textExerciseTemplate,
                    problemStatement: `Topic: ${topic.name}\nPedagogical frame: ${frame.label}\n${frame.instruction}`,
                    exampleSolution: `A deterministic validation answer for ${topic.name} (${frame.label}).`,
                    gradingInstructions: `Assess whether the answer addresses ${topic.name} using the ${frame.label.toLowerCase()} frame.`,
                });
                exercises.push(exercise);
            }
        }
        expect(exercises).toHaveLength(VALIDATION_EXERCISE_COUNT);

        const validationStartedAt = new Date().toISOString();
        const manualRuns: OrchestrationEvidence[] = [];
        const manuallyTriggeredExercises = [exercises[0], exercises[20], exercises[40]];
        // Deliberately sequential: each request must finish before the next paid orchestration starts.
        for (const exercise of manuallyTriggeredExercises) {
            manualRuns.push(await runOrchestration(page, exercise));
        }
        const validationCompletedAt = new Date().toISOString();

        await fs.mkdir(VALIDATION_OUTPUT_DIRECTORY, { recursive: true });
        const outputFile = path.join(VALIDATION_OUTPUT_DIRECTORY, `atlas-large-course-validation-${course.id}-${Date.now()}.json`);
        await fs.writeFile(
            outputFile,
            `${JSON.stringify(
                {
                    courseId: course.id,
                    courseTitle: course.title,
                    autoOrchestrationEnabled: false,
                    competencyCount: competencies.length,
                    exerciseCount: exercises.length,
                    manualExerciseIds: manuallyTriggeredExercises.map((exercise) => exercise.id),
                    validationStartedAt,
                    validationCompletedAt,
                    manualRuns,
                },
                null,
                2,
            )}\n`,
            'utf8',
        );
        console.log(`Atlas large-course validation evidence written to ${outputFile}`);
        console.log(`Disposable validation course ${course.id} intentionally retained for token-query evidence collection; delete it after the evidence handoff.`);
    });
});
