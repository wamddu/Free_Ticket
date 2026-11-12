import { useAuth } from "../../context/AuthContext";
import { useNavigate } from "react-router-dom";
import { useState } from "react";
import axios from "axios";
import EmailVerification from "../../components/EmailVerification";

import { updateMyInfoApi } from "../../api/auth";
import type { UpdateMyInfoRequest } from "../../types/Auth";

export default function ProfileModify() {
  const { user, setUser } = useAuth();
  const [usernameInput, setUsernameInput] = useState<string | undefined>(user?.username);
  const [emailInput, setEmailInput] = useState<string | undefined>(user?.email);
  const [emailVerificationToken, setEmailVerificationToken] = useState("");
  const [phonenumberInput, setPhonenumberInput] = useState<string | undefined>(user?.phonenumber);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const navigate = useNavigate();

  const handleClickSaveButton = async () => {
    // 유효성 검사
    if ((typeof usernameInput === "string" && typeof emailInput === "string" && typeof phonenumberInput === "string") &&
      (usernameInput.trim() === "" ||
        emailInput.trim() === "" ||
        phonenumberInput.trim() === "")
    ) {
      setErrorMessage("정보를 비워둘 수 없습니다.");
      return;
    }

    if (typeof usernameInput === "string") {
      if (usernameInput.length < 2) {
        setErrorMessage("사용자 이름은 2자 이상이어야 합니다.");
        return;
      }
    }

    if (typeof emailInput === "string") {
      const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

      if (!emailRegex.test(emailInput)) {
        setErrorMessage("올바른 이메일 형식이 아닙니다.");
        return;
      }
    }

    if (typeof phonenumberInput === "string") {
      if (phonenumberInput.length !== 11) {
        setErrorMessage("올바른 전화번호 형식이 아닙니다.");
        return;
      }
    }

    try {
      const requestData:UpdateMyInfoRequest = {};

      if (usernameInput !== user?.username) {
        requestData.username = usernameInput;
      }
      if (emailInput !== user?.email) {
        if (!emailVerificationToken) {
          setErrorMessage("변경할 이메일의 인증을 완료해 주세요.");
          return;
        }
        requestData.email = emailInput;
        requestData.emailVerificationToken = emailVerificationToken;
      }
      if (phonenumberInput !== user?.phonenumber) {
        requestData.phonenumber = phonenumberInput;
      }

      if (Object.keys(requestData).length === 0) {
        setErrorMessage("변경된 정보가 없습니다.");
        return;
      }

      const data = await updateMyInfoApi(requestData);

      setUser(data.user);
      alert(data.message);
      navigate("/mypage/profile");
    } catch (error) {
      if (axios.isAxiosError(error)) {
        const message = error.response?.data?.message ?? "예상치 못한 오류로 정보수정에 실패하였습니다.";
        setErrorMessage(message);
      }
    }
  };

  const handleChangeUsernameInput = (value: string) => {
    setUsernameInput(value);
  }

  const handleChangeEmailInput = (value: string) => {
    setEmailInput(value);
    setEmailVerificationToken("");
  }

  const handleChangePhonenumberInput = (value: string) => {
    setPhonenumberInput(value);
  }

  return (
    <section>
      <h1 className="text-2xl font-bold">개인정보 수정</h1>

      <div className="mt-6 max-w-xl space-y-5">
        <div>
          <label
            htmlFor="name"
            className="mb-2 block text-sm font-medium text-gray-700"
          >
            이름
          </label>

          <input
            value={usernameInput}
            onChange={(e) => handleChangeUsernameInput(e.target.value)}
            type="text"
            className="w-full rounded-lg border border-gray-300 px-4 py-3 outline-none focus:border-black"
          />
        </div>

        <div>
          <label
            htmlFor="email"
            className="mb-2 block text-sm font-medium text-gray-700"
          >
            이메일
          </label>

          <input
            value={emailInput}
            onChange={(e) => handleChangeEmailInput(e.target.value)}
            type="email"
            className="w-full rounded-lg border border-gray-300 px-4 py-3 outline-none focus:border-black"
          />
          {emailInput !== user?.email && <EmailVerification key={emailInput} email={emailInput ?? ""}
            onVerified={setEmailVerificationToken} />}
        </div>

        <div>
          <label
            htmlFor="phone"
            className="mb-2 block text-sm font-medium text-gray-700"
          >
            전화번호
          </label>

          <input
            value={phonenumberInput}
            onChange={(e) => handleChangePhonenumberInput(e.target.value)}
            type="text"
            className="w-full rounded-lg border border-gray-300 px-4 py-3 outline-none focus:border-black"
          />
        </div>

        {errorMessage && <div className="text-red-400 text-sm ml-2">{errorMessage}</div>}

        <button
          onClick={handleClickSaveButton}
          className="mt-3 rounded-lg bg-blue-500 px-6 py-3 text-white transition-colors hover:bg-blue-400">
          저장
        </button>

      </div>
    </section>
  );
}
