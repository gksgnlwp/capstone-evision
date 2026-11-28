import { describe, expect, it } from 'vitest';
import { kakaoRouteUrl, originLabel } from './navigation';

describe('카카오맵 길안내 링크', () => {
  it('출발지가 있으면 출발지에서 충전소까지 안내한다', () => {
    expect(kakaoRouteUrl({ name: '안성휴게소(부산)', lat: 37.0, lng: 127.2 }, { name: '기흥역', lat: 37.27, lng: 127.11 }))
      .toBe(`https://map.kakao.com/link/from/${encodeURIComponent('기흥역')},37.27,127.11/to/${encodeURIComponent('안성휴게소(부산)')},37,127.2`);
  });

  it('출발지가 없으면 도착지만 넘긴다', () => {
    expect(kakaoRouteUrl({ name: '망향휴게소', lat: 36.9, lng: 127.2 })).toBe(`https://map.kakao.com/link/to/${encodeURIComponent('망향휴게소')},36.9,127.2`);
  });

  it('링크 구분자인 쉼표와 슬래시는 이름에서 뺀다', () => {
    expect(kakaoRouteUrl({ name: 'A,B/C', lat: 1, lng: 2 })).toBe(`https://map.kakao.com/link/to/${encodeURIComponent('A B C')},1,2`);
  });

  it('출발지 문구는 직접 입력한 이름을 먼저 쓴다', () => {
    expect(originLabel({ lat: 1, lng: 2 }, '서울역')).toBe('서울역');
    expect(originLabel({ lat: 1, lng: 2 }, null)).toBe('현재 위치');
    expect(originLabel(null, null)).toBe('출발지 미설정');
  });
});
