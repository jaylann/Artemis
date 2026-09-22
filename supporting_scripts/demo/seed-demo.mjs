#!/usr/bin/env node
import { request } from '@playwright/test';
import { readFile, writeFile, mkdir, rename, open, unlink } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import assert from 'node:assert/strict';
import { createHash, randomBytes } from 'node:crypto';
import { parseArgs } from 'node:util';
import { description, exercises, lectures } from './course-data.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
const stateDir = path.join(root, '.demo');
const baseURL = process.env.DEMO_URL || 'http://localhost:8082';
const usage = `Usage: seed-demo.mjs [new|seed|verify] [--batch NAME] [--refresh-content]
  new                  Create a fresh pair with an automatically generated batch name.
  new --batch NAME     Create a fresh named pair; fail if its manifest already exists.
  seed --batch NAME    Create or resume a named pair without duplicating entities.
  verify --batch NAME  Check one existing pair without creating entities.
  seed / verify       Resume or check the original Software Engineering A/B pair.
  seed --refresh-content  Update owned exercise content and lecture text in place, with a local backup.
Batch names use 1–32 lowercase letters, digits, or hyphens, starting with a letter or digit.`;
const { values, positionals } = parseArgs({
    options: { batch: { type: 'string' }, help: { type: 'boolean', short: 'h' }, 'refresh-content': { type: 'boolean' } },
    allowPositionals: true,
});
if (values.help) {
    console.log(usage);
    process.exit(0);
}
const command = positionals[0] || 'seed';
if (positionals.length > 1 || !['new', 'seed', 'verify'].includes(command)) throw Error(usage);
const batch = values.batch ?? (command === 'new' ? `${new Date().toISOString().replace(/\D/g, '').slice(0, 14)}-${randomBytes(3).toString('hex')}` : 'default');
if (!/^[a-z0-9][a-z0-9-]{0,31}$/.test(batch)) throw Error(`Invalid batch name. ${usage}`);
if (command === 'new' && batch === 'default') throw Error('The batch name "default" is reserved for the original pair. Choose another name.');
const action = command === 'new' ? 'seed' : command;
const refreshContent = values['refresh-content'] ?? false;
if (refreshContent && command !== 'seed') throw Error('--refresh-content is only supported with seed.');
const batchDir = batch === 'default' ? stateDir : path.join(stateDir, 'seeds', batch);
const manifestPath = path.join(batchDir, 'seed-manifest.json');
const batchOption = batch === 'default' ? '' : ` --batch ${batch}`;
if (!['localhost', '127.0.0.1'].includes(new URL(baseURL).hostname)) throw Error('This seed is intended for the isolated local demo.');
await mkdir(batchDir, { recursive: true });
const lockPath = path.join(stateDir, 'seed.lock');
let lock;
try {
    lock = await open(lockPath, 'wx');
} catch (error) {
    if (error.code !== 'EEXIST') throw error;
    const pid = Number(await readFile(lockPath, 'utf8'));
    try {
        process.kill(pid, 0);
        throw Error(`Seed process ${pid} is already running.`);
    } catch (probe) {
        if (probe.code !== 'ESRCH') throw probe;
    }
    await unlink(lockPath);
    lock = await open(lockPath, 'wx');
}
await lock.writeFile(String(process.pid));
let api;
try {
    let manifest;
    let fresh = false;
    try {
        manifest = JSON.parse(await readFile(manifestPath, 'utf8'));
        if (command === 'new') throw Error(`Batch ${batch} already exists. Use seed --batch ${batch} to resume it, or choose another name.`);
    } catch (error) {
        if (error.code !== 'ENOENT' || action === 'verify') throw error;
        const anchor = new Date();
        anchor.setUTCHours(10, 0, 0, 0);
        const year = anchor.getUTCFullYear() % 100;
        const month = anchor.getUTCMonth();
        const semester = month < 3 ? `WS${year - 1}/${year}` : month < 9 ? `SS${year}` : `WS${year}/${year + 1}`;
        manifest = {
            version: 2,
            baseURL,
            batch,
            anchor: anchor.toISOString(),
            titlePrefix: batch === 'default' ? 'Software Engineering' : `Software Engineering — ${batch}`,
            shortNamePrefix: batch === 'default' ? 'sedemo' : `se${createHash('sha256').update(batch).digest('hex').slice(0, 12)}`,
            semester,
            courses: {},
        };
        fresh = true;
    }
    assert.equal(manifest.baseURL, baseURL, 'Manifest belongs to another server');
    assert.equal(manifest.batch ?? 'default', batch, 'Manifest belongs to another batch');
    const titlePrefix = manifest.titlePrefix ?? 'Software Engineering';
    const shortNamePrefix = manifest.shortNamePrefix ?? 'sedemo';
    const save = async () => {
        await writeFile(`${manifestPath}.tmp`, JSON.stringify(manifest, null, 2) + '\n', { mode: 0o600 });
        await rename(`${manifestPath}.tmp`, manifestPath);
    };
    const date = (days, minutes = 0) => new Date(Date.parse(manifest.anchor) + days * 86400000 + minutes * 60000).toISOString();
    api = await request.newContext({ baseURL, timeout: 180000 });
    const call = async (method, url, options = {}) => {
        const response = await api.fetch(url, { method, ...options });
        if (!response.ok()) throw Error(`${method} ${url}: HTTP ${response.status()} ${(await response.text()).slice(0, 1600)}`);
        const text = await response.text();
        return text ? JSON.parse(text) : undefined;
    };
    const multipart = (name, data) => ({ [name]: { name: `${name}.json`, mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(data)) } });
    await call('POST', '/api/core/public/authenticate', {
        data: { username: process.env.DEMO_USER || 'artemis_admin', password: process.env.DEMO_PASSWORD || 'artemis_admin', rememberMe: false },
    });
    const allCourses = await call('GET', '/api/course/courses');
    const courseList = Array.isArray(allCourses) ? allCourses : allCourses.content;
    assert.ok(Array.isArray(courseList), 'Unexpected course-list response');
    if (fresh) {
        for (const suffix of ['a', 'b']) {
            assert.ok(
                !courseList.some((c) => c.shortName === `${shortNamePrefix}${suffix}`),
                `Course identifier already exists for batch ${batch}; restore its manifest or choose another batch.`,
            );
        }
    }
    if (action === 'seed') {
        await save();
        await call('PUT', '/api/admin/feature-toggle', { data: { AtlasAgent: true } });
    }
    console.log(`Batch: ${batch}\nManifest: ${manifestPath}\nResume: supporting_scripts/demo/run-demo.sh seed${batchOption}${refreshContent ? ' --refresh-content' : ''}`);
    const modules = { programming: 'programming', quiz: 'quiz', text: 'text', modeling: 'modeling', 'file-upload': 'fileupload' };
    const resource = (type) => `${type === 'file-upload' ? 'file-upload' : type}-exercises`;
    const exerciseUrl = (type, id) => `/api/${modules[type]}/${resource(type)}/${id}`;
    const courseExercises = async (id) => {
        const rows = [];
        for (const type of Object.keys(modules)) {
            const result = await call('GET', `/api/${modules[type]}/courses/${id}/${resource(type)}`);
            rows.push(...result.map((exercise) => ({ ...exercise, type })));
        }
        return rows;
    };
    const ensure = async (records, key, existing, create) => {
        if (records[key]) {
            const found = existing.find((x) => x.id === records[key]);
            assert.ok(found, `Manifest entity ${key}/${records[key]} is missing; the database may have been replaced.`);
            return found;
        }
        assert.ok(existing.length <= 1, `More than one entity matches ${key}`);
        const entity = existing[0] || (await create());
        records[key] = entity.id;
        await save();
        return entity;
    };
    const comparisons = [];
    for (const suffix of ['A', 'B']) {
        const shortName = `${shortNamePrefix}${suffix.toLowerCase()}`;
        const expectedCourse = {
            title: `${titlePrefix} — ${suffix}`,
            shortName,
            description,
            semester: manifest.semester ?? 'WS26/27',
            startDate: date(-21),
            endDate: date(100),
            testCourse: false,
            onlineCourse: false,
            language: 'ENGLISH',
            defaultProgrammingLanguage: 'JAVA',
            timeZone: 'Europe/Berlin',
            courseInformationSharingConfiguration: 'DISABLED',
            learningPathsEnabled: false,
            enrollmentEnabled: false,
            unenrollmentEnabled: false,
            autoOrchestratorEnabled: false,
            accuracyOfScores: 1,
            maxComplaints: 0,
            maxTeamComplaints: 0,
            maxComplaintTimeDays: 0,
            maxComplaintTextLimit: 2000,
            maxComplaintResponseTextLimit: 2000,
        };
        const record = (manifest.courses[suffix] ||= { exercises: {}, lectures: {}, units: {}, repositoriesReady: {} });
        const matchingCourses = courseList.filter((c) => c.shortName === shortName);
        assert.ok(matchingCourses.length <= 1, `Duplicate course identifier ${shortName}`);
        let course = matchingCourses[0];
        if (course && !record.id) assert.equal(course.title, expectedCourse.title, 'Existing course belongs to another dataset');
        if (!course && action === 'seed' && !record.id) {
            course = await call('POST', '/api/admin/courses', { multipart: multipart('course', expectedCourse) });
        }
        assert.ok(course, `Missing course ${shortName}`);
        if (record.id) assert.equal(course.id, record.id, 'Course identity changed');
        record.id = course.id;
        if (action === 'seed') await save();
        console.log(`${action}: ${expectedCourse.title} (course ${course.id})`);
        const existingExercises = await courseExercises(course.id);
        if (refreshContent) {
            const backup = { savedAt: new Date().toISOString(), courseId: course.id, exercises: [], lectures: [] };
            for (const spec of exercises) {
                const id = record.exercises[spec.key];
                if (!id) continue;
                assert.ok(
                    existingExercises.some((e) => e.id === id && e.type === spec.type),
                    `Exercise ${id} is not owned by this course`,
                );
                const current = await call('GET', exerciseUrl(spec.type, id));
                if (spec.type === 'quiz') assert.equal(current.isEditable, true, `Quiz ${id} is no longer editable; content refresh stopped`);
                backup.exercises.push(current);
            }
            for (const id of Object.values(record.lectures)) backup.lectures.push(await call('GET', `/api/lecture/lectures/${id}/details`));
            const backupPath = path.join(batchDir, `content-backup-${course.id}-${Date.now()}.json`);
            await writeFile(backupPath, JSON.stringify(backup, null, 2) + '\n', { mode: 0o600, flag: 'wx' });
            console.log(`Content backup: ${backupPath}`);
        }
        for (let i = 0; i < exercises.length; i++) {
            const spec = exercises[i];
            const common = {
                ...spec,
                key: undefined,
                course: { id: course.id },
                courseId: course.id,
                shortName: spec.key,
                mode: 'INDIVIDUAL',
                includedInOverallScore: 'INCLUDED_COMPLETELY',
                releaseDate: date(-14 + Math.floor(i / 2) * 2),
                dueDate: date(7 + i * 2),
                assessmentDueDate: date(14 + i * 2),
                bonusPoints: 0,
                assessmentType: 'MANUAL',
                secondCorrectionEnabled: false,
                categories: [JSON.stringify({ category: 'Software Engineering', color: '#3b82f6' })],
            };
            const matches = existingExercises.filter((e) => (record.exercises[spec.key] ? e.id === record.exercises[spec.key] : e.title === spec.title));
            let e;
            if (action === 'seed') {
                e = await ensure(record.exercises, spec.key, matches, async () => {
                    if (spec.type === 'quiz') {
                        const body = { ...common, quizMode: 'INDIVIDUAL', randomizeQuestionOrder: false, duration: 900 };
                        return call('POST', `/api/quiz/courses/${course.id}/quiz-exercises`, { multipart: multipart('exercise', body) });
                    }
                    if (spec.type === 'programming') {
                        const fixture = JSON.parse(await readFile(path.join(root, 'src/test/playwright/fixtures/exercise/programming/java/template.json'), 'utf8'));
                        return call('POST', '/api/programming/programming-exercises/setup', {
                            data: {
                                ...fixture,
                                ...common,
                                packageName: 'edu.demo',
                                assessmentType: 'AUTOMATIC',
                                programmingLanguage: 'JAVA',
                                projectType: 'PLAIN_MAVEN',
                                allowOfflineIde: true,
                                allowOnlineEditor: true,
                                staticCodeAnalysisEnabled: false,
                                buildConfig: { checkoutSolutionRepository: false },
                            },
                        });
                    }
                    return call('POST', `/api/${modules[spec.type]}/${resource(spec.type)}`, { data: common });
                });
                if (refreshContent) {
                    const current = await call('GET', exerciseUrl(spec.type, e.id));
                    const desired = exerciseContent(spec);
                    if (!contentMatches(current, desired)) {
                        const body = { ...current, ...desired, courseId: course.id };
                        if (spec.type === 'programming') body.solutionRepositoryUri = current.solutionParticipation.repositoryUri;
                        if (spec.type === 'quiz') {
                            body.quizQuestions = spec.quizQuestions.map((q, index) => ({
                                ...q,
                                id: current.quizQuestions[index]?.id,
                                answerOptions: q.answerOptions.map((a, j) => ({ ...a, id: current.quizQuestions[index]?.answerOptions[j]?.id })),
                            }));
                            await call('PUT', exerciseUrl(spec.type, e.id), { multipart: multipart('exercise', body) });
                        } else {
                            const url = spec.type === 'file-upload' ? exerciseUrl(spec.type, e.id) : `/api/${modules[spec.type]}/${resource(spec.type)}`;
                            await call('PUT', url, { data: body });
                        }
                        console.log(`Updated: ${spec.title}`);
                    }
                }
                if (spec.type === 'programming' && record.repositoriesReady[spec.key] !== 2) {
                    const full = await call('GET', exerciseUrl(spec.type, e.id));
                    assert.ok(full.templateParticipation?.id && full.solutionParticipation?.id, 'Programming setup did not create base participations');
                    if (spec.key === 'collections') await seedCollectionRepositories(call, full);
                    record.repositoriesReady[spec.key] = 2;
                    await save();
                }
            } else {
                assert.equal(matches.length, 1, `Expected exactly one ${spec.title}`);
                e = matches[0];
                assert.equal(record.exercises[spec.key], e.id);
            }
        }
        const existingLectures = await call('GET', `/api/lecture/courses/${course.id}/lectures`);
        for (let i = 0; i < lectures.length; i++) {
            const spec = lectures[i],
                key = `lecture${i}`;
            const matches = existingLectures.filter((l) => l.title === spec.title);
            const lecture =
                action === 'seed'
                    ? await ensure(record.lectures, key, matches, () =>
                          call('POST', '/api/lecture/lectures', {
                              data: {
                                  title: spec.title,
                                  description: `Lecture ${i + 1}: ${spec.units.map((u) => u.name).join(' and ')}.`,
                                  course: { id: course.id },
                                  courseId: course.id,
                                  startDate: date(-14 + i * 7),
                                  endDate: date(-14 + i * 7, 90),
                              },
                          }),
                      )
                    : matches[0];
            assert.ok(lecture, `Missing ${spec.title}`);
            const details = await call('GET', `/api/lecture/lectures/${lecture.id}/details`);
            const units = details.lectureUnits || [];
            for (let j = 0; j < spec.units.length; j++) {
                const unit = spec.units[j],
                    unitKey = `${key}unit${j}`;
                const matches = units.filter((u) => u.name === unit.name);
                if (action === 'seed') {
                    const savedUnit = await ensure(record.units, unitKey, matches, () =>
                        call('POST', `/api/lecture/lectures/${lecture.id}/text-units`, {
                            data: {
                                type: 'text',
                                ...unit,
                                releaseDate: date(-21),
                            },
                        }),
                    );
                    if (refreshContent && savedUnit.content !== unit.content)
                        await call('PUT', `/api/lecture/lectures/${lecture.id}/text-units`, { data: { ...savedUnit, ...unit } });
                } else assert.equal(matches.length, 1, `Missing or duplicated unit ${unit.name}`);
            }
        }
        comparisons.push(await verifyCourse(call, course.id, courseExercises, exerciseUrl));
    }
    assert.deepEqual(comparisons[0], comparisons[1], 'The two course datasets differ');
    const finalCourses = await call('GET', '/api/course/courses');
    const finalCourseList = Array.isArray(finalCourses) ? finalCourses : finalCourses.content;
    const batchIds = Object.values(manifest.courses).map((c) => c.id);
    assert.equal(new Set(batchIds).size, 2, 'Expected two distinct courses in this batch');
    assert.equal(finalCourseList.filter((c) => batchIds.includes(c.id)).length, 2, 'Expected exactly two courses in this batch');
    if (action === 'seed') await save();
    await writeFile(
        path.join(batchDir, 'verification.json'),
        JSON.stringify(
            {
                verifiedAt: new Date().toISOString(),
                batch,
                courses: manifest.courses,
                counts: { courses: 2, exercisesPerCourse: 10, lecturesPerCourse: 3, unitsPerLecture: 2 },
                normalizedEqual: true,
            },
            null,
            2,
        ) + '\n',
    );
    console.log('Verified: two matching courses; each has 10 exercises (2 per type), 3 lectures, and 6 text units.');
    console.log(`Verify: supporting_scripts/demo/run-demo.sh verify${batchOption}`);
    for (const suffix of ['A', 'B']) console.log(`${suffix}: ${baseURL}/course-management/${manifest.courses[suffix].id}/exercises`);
} catch (error) {
    console.error(
        `Batch ${batch} failed: ${error.message}\nResume incomplete creation: supporting_scripts/demo/run-demo.sh seed${batchOption}${refreshContent ? ' --refresh-content' : ''}`,
    );
    process.exitCode = 1;
} finally {
    await api?.dispose();
    await lock.close();
    await unlink(lockPath);
}

async function verifyCourse(call, courseId, courseExercises, exerciseUrl) {
    const actual = await courseExercises(courseId);
    assert.equal(actual.length, 10, 'Exercise count');
    const contents = [];
    const fields = [
        'title',
        'type',
        'problemStatement',
        'gradingInstructions',
        'difficulty',
        'maxPoints',
        'bonusPoints',
        'mode',
        'includedInOverallScore',
        'releaseDate',
        'dueDate',
        'assessmentDueDate',
        'exampleSolution',
        'exampleSolutionExplanation',
        'diagramType',
        'filePattern',
        'programmingLanguage',
        'projectType',
        'packageName',
        'duration',
        'quizMode',
    ];
    for (const spec of exercises) {
        const found = actual.filter((e) => e.title === spec.title);
        assert.equal(found.length, 1, `Exercise ${spec.title}`);
        const e = await call('GET', exerciseUrl(spec.type, found[0].id));
        assert.equal(e.type, spec.type);
        assert.ok(contentMatches(e, exerciseContent(spec)), `Content differs for ${spec.title}; use seed --refresh-content to apply the current dataset`);
        assert.ok(!e.competencyLinks?.length, 'Unexpected competency links');
        const normalized = Object.fromEntries(fields.map((k) => [k, e[k] ?? null]));
        if (spec.type === 'quiz') {
            assert.equal(e.quizQuestions.length, spec.quizQuestions.length);
            normalized.quizQuestions = e.quizQuestions.map((q) => ({
                title: q.title,
                text: q.text,
                points: q.points,
                explanation: q.explanation,
                answerOptions: q.answerOptions.map((a) => ({ text: a.text, isCorrect: a.isCorrect, explanation: a.explanation })),
            }));
        }
        if (spec.type === 'programming') {
            for (const role of ['templateParticipation', 'solutionParticipation']) {
                assert.ok(e[role]?.repositoryUri, `Missing ${role} repository`);
                const files = await call('GET', `/api/programming/participations/${e[role].id}/repository/files`);
                assert.ok(Object.keys(files).length > 0, `Empty ${role} repository`);
            }
            const tests = await call('GET', `/api/programming/programming-exercises/${e.id}/test-repository/files`);
            assert.ok(Object.keys(tests).length > 0, 'Empty test repository');
        }
        contents.push(normalized);
    }
    const ls = await call('GET', `/api/lecture/courses/${courseId}/lectures`);
    assert.equal(ls.length, 3);
    const lectureContents = [];
    for (const expected of lectures) {
        const l = ls.find((x) => x.title === expected.title);
        assert.ok(l);
        const details = await call('GET', `/api/lecture/lectures/${l.id}/details`);
        assert.equal(details.lectureUnits.length, 2);
        const units = details.lectureUnits
            .map((u) => ({ name: u.name, content: u.content, type: u.type, releaseDate: u.releaseDate }))
            .sort((a, b) => a.name.localeCompare(b.name));
        for (const u of expected.units) assert.equal(units.find((x) => x.name === u.name)?.content, u.content);
        lectureContents.push({ title: l.title, description: l.description, startDate: l.startDate, endDate: l.endDate, units });
    }
    const competencies = await call('GET', `/api/atlas/courses/${courseId}/competencies`);
    assert.equal(competencies.length, 0, 'Seed must not create competencies');
    const course = await call('GET', `/api/course/courses/${courseId}`);
    assert.equal(course.courseConfiguration?.autoOrchestratorEnabled ?? false, false, 'Automatic orchestration must remain disabled');
    const courseFields = [
        'description',
        'semester',
        'startDate',
        'endDate',
        'language',
        'timeZone',
        'defaultProgrammingLanguage',
        'testCourse',
        'onlineCourse',
        'courseInformationSharingConfiguration',
        'learningPathsEnabled',
        'enrollmentEnabled',
        'unenrollmentEnabled',
        'accuracyOfScores',
    ];
    const normalizedCourse = Object.fromEntries(courseFields.map((key) => [key, course[key] ?? null]));
    return { course: normalizedCourse, exercises: contents, lectures: lectureContents };
}

function exerciseContent(spec) {
    const fields = ['title', 'problemStatement', 'gradingInstructions', 'difficulty', 'maxPoints', 'exampleSolution', 'exampleSolutionExplanation'];
    const content = Object.fromEntries(fields.filter((key) => spec[key] !== undefined).map((key) => [key, spec[key]]));
    if (spec.type === 'quiz') content.quizQuestions = spec.quizQuestions;
    return content;
}

function contentMatches(actual, expected) {
    return Object.entries(expected).every(([key, value]) => {
        if (key !== 'quizQuestions') return actual[key] === value;
        const normalize = (questions) =>
            questions?.map((q) => ({
                title: q.title,
                text: q.text,
                points: q.points,
                explanation: q.explanation,
                scoringType: q.scoringType,
                singleChoice: q.singleChoice,
                randomizeOrder: q.randomizeOrder,
                answerOptions: q.answerOptions.map((a) => ({ text: a.text, isCorrect: a.isCorrect, explanation: a.explanation })),
            }));
        return JSON.stringify(normalize(actual[key])) === JSON.stringify(normalize(value));
    });
}

async function seedCollectionRepositories(call, e) {
    const source = (solution) => `package edu.demo;
import java.util.*;
public class CollectionProcessor {
    public static List<String> uniqueSortedTitles(List<String> titles) {
        ${solution ? 'Objects.requireNonNull(titles);\n        var normalized = new TreeSet<String>();\n        for (String title : titles) {\n            String value = Objects.requireNonNull(title).trim();\n            if (!value.isEmpty()) normalized.add(value);\n        }\n        return new ArrayList<>(normalized);' : '// TODO Normalize, deduplicate, and sort without changing titles.\n        throw new UnsupportedOperationException("Implement uniqueSortedTitles");'}
    }
    public static int totalCharacters(List<String> titles) {
        ${solution ? 'return uniqueSortedTitles(titles).stream().mapToInt(String::length).sum();' : '// TODO Reuse the normalization operation.\n        throw new UnsupportedOperationException("Implement totalCharacters");'}
    }
}
`;
    for (const [role, solution] of [
        ['templateParticipation', false],
        ['solutionParticipation', true],
    ]) {
        const url = `/api/programming/participations/${e[role].id}/repository`;
        const files = await call('GET', `${url}/files`);
        for (const name of Object.keys(files).filter((name) => name.startsWith('src/') && name.endsWith('.java') && !name.endsWith('/CollectionProcessor.java'))) {
            await call('DELETE', `${url}/file?file=${encodeURIComponent(name)}`);
        }
        await putRepositoryFile(call, url, 'src/edu/demo/CollectionProcessor.java', source(solution));
    }
    const testRoot = `/api/programming/programming-exercises/${e.id}/test-repository`;
    const files = await call('GET', `${testRoot}/files`);
    const behaviorPath =
        Object.keys(files).find((p) => p.endsWith('CollectionProcessorTest.java')) || Object.keys(files).find((p) => p.endsWith('SortingExampleBehaviorTest.java'));
    assert.ok(behaviorPath, 'Could not locate generated test source directory');
    const testPath = behaviorPath.replace('SortingExampleBehaviorTest.java', 'CollectionProcessorTest.java');
    for (const name of Object.keys(files).filter((name) => name.endsWith('.java') && name !== testPath)) {
        await call('DELETE', `${testRoot}/file?file=${encodeURIComponent(name)}`);
    }
    const test = `package edu.demo;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
class CollectionProcessorTest {
    @Test void normalizesAndDeduplicates() { assertEquals(List.of("Algorithms", "Patterns"), CollectionProcessor.uniqueSortedTitles(List.of("  Patterns ", "Algorithms", "Patterns", " "))); }
    @Test void countsNormalizedCharacters() { assertEquals(18, CollectionProcessor.totalCharacters(List.of("Patterns", " Algorithms ", "Patterns"))); }
    @Test void preservesInput() { var input = new ArrayList<>(List.of(" B ", "A")); CollectionProcessor.uniqueSortedTitles(input); assertEquals(List.of(" B ", "A"), input); }
    @Test void acceptsEmptyInput() { assertEquals(List.of(), CollectionProcessor.uniqueSortedTitles(List.of())); assertEquals(0, CollectionProcessor.totalCharacters(List.of())); }
    @Test void preservesCase() { assertEquals(List.of("Java", "java"), CollectionProcessor.uniqueSortedTitles(List.of("java", "Java"))); }
    @Test void rejectsNulls() { assertThrows(NullPointerException.class, () -> CollectionProcessor.uniqueSortedTitles(null)); assertThrows(NullPointerException.class, () -> CollectionProcessor.uniqueSortedTitles(Arrays.asList("A", null))); }
}
`;
    await putRepositoryFile(call, testRoot, testPath, test);
}
async function putRepositoryFile(call, url, fileName, fileContent) {
    const files = await call('GET', `${url}/files`);
    if (!(fileName in files)) await call('POST', `${url}/file?file=${encodeURIComponent(fileName)}`);
    // Commits here are exercise content inside the isolated local VCS, never Artemis source commits.
    await call('PUT', `${url}/files?commit=true`, { data: [{ fileName, fileContent }] });
}
