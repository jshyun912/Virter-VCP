import { render, screen, waitFor } from '@testing-library/svelte';
import { userEvent } from '@testing-library/user-event';
import { tick } from 'svelte';
import { afterEach, expect, test, vi } from 'vitest';
import Page from './+page.svelte';

const contest = {
	id: 2037,
	name: 'Codeforces Round 2037',
	durationSeconds: 7200
};

afterEach(() => vi.unstubAllGlobals());

function successfulResponse() {
	return {
		ok: true,
		json: vi.fn().mockResolvedValue({ contestList: [contest] })
	} as unknown as Response;
}

async function addHandle(user: ReturnType<typeof userEvent.setup>, handle: string) {
	await user.type(screen.getByRole('textbox', { name: 'Codeforces 핸들' }), `${handle} `);
}

test('삭제 버튼으로 마지막 핸들을 지우면 난이도와 이전 결과를 초기화한다', async () => {
	vi.stubGlobal('fetch', vi.fn().mockResolvedValue(successfulResponse()));
	const user = userEvent.setup();
	render(Page);

	await addHandle(user, 'tourist');
	await user.click(screen.getAllByRole('checkbox')[0]);
	await user.click(screen.getByRole('button', { name: '대회 찾기' }));
	expect(await screen.findByRole('link', { name: /Codeforces Round 2037/ })).toBeInTheDocument();

	await user.click(screen.getByRole('button', { name: 'tourist 삭제' }));
	await addHandle(user, 'Benq');

	expect(
		screen.getAllByRole('checkbox').every((checkbox) => (checkbox as HTMLInputElement).checked)
	).toBe(true);
	expect(screen.queryByRole('link', { name: /Codeforces Round 2037/ })).not.toBeInTheDocument();
});

test('Backspace로 마지막 핸들을 지워도 난이도를 초기화한다', async () => {
	const user = userEvent.setup();
	render(Page);

	await addHandle(user, 'tourist');
	await user.click(screen.getAllByRole('checkbox')[0]);
	await user.click(screen.getByRole('textbox', { name: 'Codeforces 핸들' }));
	await user.keyboard('{Backspace}');
	await addHandle(user, 'Benq');

	expect(
		screen.getAllByRole('checkbox').every((checkbox) => (checkbox as HTMLInputElement).checked)
	).toBe(true);
});

test('마지막 핸들 삭제 후 도착한 이전 검색 결과를 표시하지 않는다', async () => {
	let resolveResponse: (response: Response) => void = () => {};
	const responsePromise = new Promise<Response>((resolve) => {
		resolveResponse = resolve;
	});
	vi.stubGlobal('fetch', vi.fn().mockReturnValue(responsePromise));
	const user = userEvent.setup();
	render(Page);

	await addHandle(user, 'tourist');
	await user.click(screen.getByRole('button', { name: '대회 찾기' }));
	await user.click(screen.getByRole('button', { name: 'tourist 삭제' }));
	await addHandle(user, 'Benq');

	const response = successfulResponse();
	resolveResponse(response);
	await waitFor(() => expect(response.json).toHaveBeenCalled());
	await tick();

	expect(screen.queryByRole('link', { name: /Codeforces Round 2037/ })).not.toBeInTheDocument();
});

test('Codeforces 서버 오류를 결과 영역의 경고 카드로 표시한다', async () => {
	vi.stubGlobal(
		'fetch',
		vi.fn().mockResolvedValue({
			ok: false,
			json: vi.fn().mockResolvedValue({
				status: 'FAILED',
				message: 'CODEFORCES_API_FAILED'
			})
		})
	);
	const user = userEvent.setup();
	render(Page);

	await addHandle(user, 'tourist');
	await user.click(screen.getByRole('button', { name: '대회 찾기' }));

	expect(await screen.findByRole('alert')).toHaveTextContent(
		'Codeforces와 연결이 불안정합니다. 잠시 후 다시 시도해 주세요.'
	);
});

test('네트워크 오류를 일반 오류 카드로 표시한다', async () => {
	vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));
	const user = userEvent.setup();
	render(Page);

	await addHandle(user, 'tourist');
	await user.click(screen.getByRole('button', { name: '대회 찾기' }));

	expect(await screen.findByRole('alert')).toHaveTextContent(
		'검색 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.'
	);
});
