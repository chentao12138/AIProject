/**
 * Live API journey against the running backend (verification window).
 *
 * Deliberately API-level, not GUI: it proves the contract behaviours the unit
 * suite can only assert against mocks — the practice result read and its two
 * 409 codes, `deadlineAt` on an attempt, the DRAFT-only question edit, the
 * ungraded attempt's null score, and the new ORDERING shape rule.
 */

const BASE = process.env.AISTUDY_API_BASE_URL ?? 'http://127.0.0.1:8080';
const USER = process.env.AISTUDY_USER ?? '';
// Never a literal: a committed harness must not carry a password, even a dev one.
// An empty value makes login fail, and the script aborts with "no token".
const PASS = process.env.AISTUDY_PASS ?? '';

let token = null;

async function call(method, path, body) {
  const res = await fetch(BASE + path, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  let data = null;
  try {
    data = await res.json();
  } catch {
    data = null;
  }
  return { status: res.status, data };
}

const rows = [];
function check(name, pass, detail) {
  rows.push({ name, pass });
  console.log(`${pass ? 'PASS' : 'FAIL'} | ${name} | ${detail}`);
}

// 1. login
const login = await call('POST', '/api/v1/auth/login', { username: USER, password: PASS });
check('login', login.status === 200 && Boolean(login.data?.accessToken), `HTTP ${login.status}`);
if (!login.data?.accessToken) {
  console.log(`ABORT: no token (HTTP ${login.status}) — set AISTUDY_USER / AISTUDY_PASS`);
  process.exit(1);
}
token = login.data.accessToken;

// 2. a space of our own
const spaces = await call('GET', '/api/v1/spaces');
let spaceId = spaces.data?.[0]?.id ?? spaces.data?.items?.[0]?.id;
if (!spaceId) {
  const created = await call('POST', '/api/v1/spaces', {
    name: '前端联调验收空间',
    description: 'R04/R05 live journey',
    visibility: 'PRIVATE',
  });
  spaceId = created.data?.id;
  check('space created', Boolean(spaceId), `HTTP ${created.status} id=${spaceId}`);
} else {
  check('space reused', true, `id=${spaceId}`);
}

// 3. ORDERING: the shape the editor actually sends (options + full permutation)
const orderingBody = {
  questionType: 'ORDERING',
  stem: '把步骤按顺序排好。',
  difficulty: 'EASY',
  options: [
    { optionKey: 'A', content: '第一步' },
    { optionKey: 'B', content: '第二步' },
    { optionKey: 'C', content: '第三步' },
  ],
  correctOrder: [1, 2, 3],
};
const ordering = await call('POST', `/api/v1/spaces/${spaceId}/questions`, orderingBody);
check(
  'ORDERING create (options + correctOrder)',
  ordering.status === 201 || ordering.status === 200,
  `HTTP ${ordering.status} id=${ordering.data?.id ?? JSON.stringify(ordering.data)?.slice(0, 120)}`
);
const orderingId = ordering.data?.id;

// 3b. the new rule: ORDERING without options must be refused (400), not stored
const noOptions = await call('POST', `/api/v1/spaces/${spaceId}/questions`, {
  questionType: 'ORDERING',
  stem: '只有顺序没有选项。',
  difficulty: 'EASY',
  correctOrder: [1, 2],
});
check(
  'ORDERING without options is rejected',
  noOptions.status === 400,
  `HTTP ${noOptions.status} code=${noOptions.data?.code ?? '-'}`
);

// 4. read the stored order back (C09b)
const detail = await call('GET', `/api/v1/spaces/${spaceId}/questions/${orderingId}`);
check(
  'QuestionAnswerView returns correctOrder (C09b)',
  Array.isArray(detail.data?.answer?.correctOrder) &&
    detail.data.answer.correctOrder.join(',') === '1,2,3',
  `answer=${JSON.stringify(detail.data?.answer)}`
);

// 5. DRAFT-only edit (C05): draft ok, published refused with the documented code
const draftPut = await call('PUT', `/api/v1/spaces/${spaceId}/questions/${orderingId}`, {
  stem: '把下面的步骤按顺序排好。',
  explanation: '顺序即判分依据。',
  difficulty: 'EASY',
});
check('PUT on a DRAFT succeeds', draftPut.status === 200, `HTTP ${draftPut.status}`);

const publish = await call(
  'POST',
  `/api/v1/spaces/${spaceId}/questions/${orderingId}/publish`,
  {}
);
check('publish the draft', publish.status === 200 || publish.status === 201, `HTTP ${publish.status}`);

const publishedPut = await call(
  'PUT',
  `/api/v1/spaces/${spaceId}/questions/${orderingId}`,
  { stem: '改不动。', explanation: '', difficulty: 'EASY' }
);
check(
  'PUT on a PUBLISHED question is 409 QUESTION_NOT_EDITABLE (C05)',
  publishedPut.status === 409 && publishedPut.data?.code === 'QUESTION_NOT_EDITABLE',
  `HTTP ${publishedPut.status} code=${publishedPut.data?.code ?? '-'}`
);

// 6. practice: result before finish, then after
const session = await call('POST', `/api/v1/spaces/${spaceId}/practice-sessions`, {
  questionIds: [orderingId],
});
const sessionId = session.data?.id ?? session.data?.sessionId;
check('practice session created', Boolean(sessionId), `HTTP ${session.status} id=${sessionId}`);

const earlyResult = await call(
  'GET',
  `/api/v1/spaces/${spaceId}/practice-sessions/${sessionId}/result`
);
check(
  'result before finish is 409 PRACTICE_SESSION_NOT_SUBMITTED',
  earlyResult.status === 409 &&
    earlyResult.data?.code === 'PRACTICE_SESSION_NOT_SUBMITTED',
  `HTTP ${earlyResult.status} code=${earlyResult.data?.code ?? '-'} detail=${String(
    earlyResult.data?.detail ?? ''
  ).slice(0, 60)}`
);

await call('POST', `/api/v1/spaces/${spaceId}/practice-sessions/${sessionId}/start`, {});
const ps = await call('GET', `/api/v1/spaces/${spaceId}/practice-sessions/${sessionId}`);
const slot = (ps.data?.questions ?? [])[0];
check('session exposes one question slot', Boolean(slot?.practiceSessionQuestionId), `slot=${slot?.practiceSessionQuestionId}`);

const answered = await call(
  'POST',
  `/api/v1/spaces/${spaceId}/practice-sessions/${sessionId}/answers`,
  {
    practiceSessionQuestionId: slot.practiceSessionQuestionId,
    answer: { orderingAnswer: [1, 2, 3] },
  }
);
check(
  'ORDERING answer accepted as number[]',
  answered.status === 200 || answered.status === 201,
  `HTTP ${answered.status} isCorrect=${answered.data?.isCorrect ?? '-'}`
);

const finished = await call(
  'POST',
  `/api/v1/spaces/${spaceId}/practice-sessions/${sessionId}/finish`,
  {}
);
check('finish', finished.status === 200, `HTTP ${finished.status}`);

const result = await call(
  'GET',
  `/api/v1/spaces/${spaceId}/practice-sessions/${sessionId}/result`
);
check(
  'result after finish is readable (A5.3 / C06)',
  result.status === 200 && typeof result.data?.score === 'number',
  `HTTP ${result.status} body=${JSON.stringify(result.data)}`
);

const answersList = await call(
  'GET',
  `/api/v1/spaces/${spaceId}/practice-sessions/${sessionId}/answers`
);
const firstAnswer = (answersList.data ?? [])[0] ?? answersList.data?.items?.[0];
check(
  'answers list carries explanation now (the null defect fix)',
  answersList.status === 200 && firstAnswer?.explanation !== undefined,
  `HTTP ${answersList.status} explanation=${JSON.stringify(firstAnswer?.explanation ?? null)}`
);

// 7. exam: deadlineAt + attempt history semantics
const exam = await call('POST', `/api/v1/spaces/${spaceId}/exams`, {
  title: '前端验收卷',
  description: 'live journey',
  timeLimitMinutes: 30,
  questions: [{ questionId: orderingId, score: 3, sortOrder: 0 }],
});
const examId = exam.data?.id;
check('exam created with timeLimitMinutes', Boolean(examId), `HTTP ${exam.status} id=${examId}`);

const examPublished = await call('POST', `/api/v1/spaces/${spaceId}/exams/${examId}/publish`, {});
check(
  'exam published',
  examPublished.status === 200 || examPublished.status === 201,
  `HTTP ${examPublished.status} ${String(examPublished.data?.status ?? '')}`
);

// The app's own verb: an attempt is created through the exam's `sessions` path.
const attempt = await call('POST', `/api/v1/spaces/${spaceId}/exams/${examId}/sessions`, {});
const attemptId = attempt.data?.id;
check(
  'attempt created',
  Boolean(attemptId),
  `HTTP ${attempt.status} code=${attempt.data?.code ?? '-'} id=${attemptId}`
);

if (attemptId) {
  await call('POST', `/api/v1/spaces/${spaceId}/exam-attempts/${attemptId}/start`, {});
  const attemptRead = await call(
    'GET',
    `/api/v1/spaces/${spaceId}/exam-attempts/${attemptId}`
  );
  check(
    'attempt read carries deadlineAt (the second "not run" row)',
    Boolean(attemptRead.data?.deadlineAt),
    `status=${attemptRead.data?.status} deadlineAt=${attemptRead.data?.deadlineAt ?? 'NULL'}`
  );
  const q0 = (attemptRead.data?.questions ?? [])[0];
  check(
    'attempt question reports answered=false before any answer',
    q0?.answered === false,
    `answered=${q0?.answered} answer=${JSON.stringify(q0?.answer ?? null)}`
  );

  const history = await call(
    'GET',
    `/api/v1/spaces/${spaceId}/exam-attempts?examId=${examId}&page=0&size=10`
  );
  const row = (history.data?.items ?? [])[0];
  check(
    'history row exists and is paged',
    history.status === 200 && Array.isArray(history.data?.items) && Boolean(row),
    `HTTP ${history.status} total=${history.data?.totalItems ?? '-'} rows=${history.data?.items?.length}`
  );
  check(
    'ungraded attempt has null score, not 0 (C11)',
    row ? row.score === null || row.score === undefined : false,
    `score=${JSON.stringify(row?.score ?? null)} maxScore=${JSON.stringify(row?.maxScore ?? null)} status=${row?.status}`
  );
  // The field this round asked for: the row must state its grading, not leave
  // the UI to infer it from "did a number arrive".
  check(
    'history row carries gradingStatus (C11 follow-up)',
    'gradingStatus' in (row ?? {}),
    `gradingStatus=${JSON.stringify(row?.gradingStatus ?? null)}`
  );

  // Answer the last open C11 question by measurement: submit an attempt and see
  // whether `score` arrives before a human has graded anything.
  const submitted = await call(
    'POST',
    `/api/v1/spaces/${spaceId}/exam-attempts/${attemptId}/submit`,
    {}
  );
  const historyAfter = await call(
    'GET',
    `/api/v1/spaces/${spaceId}/exam-attempts?examId=${examId}&page=0&size=10`
  );
  const submittedRow = (historyAfter.data?.items ?? []).find(
    (r) => r.id === attemptId
  );
  check(
    'submitted attempt shows gradingStatus + null score while ungraded',
    submitted.status === 200 &&
      String(submittedRow?.gradingStatus ?? '').toUpperCase() === 'UNGRADED' &&
      (submittedRow?.score === null || submittedRow?.score === undefined),
    `submit=${submitted.status} gradingStatus=${JSON.stringify(
      submittedRow?.gradingStatus ?? null
    )} score=${JSON.stringify(submittedRow?.score ?? null)}`
  );
}

const failed = rows.filter((r) => !r.pass).length;
console.log(`\nSUMMARY: ${rows.length - failed}/${rows.length} passed`);
process.exit(failed ? 2 : 0);
